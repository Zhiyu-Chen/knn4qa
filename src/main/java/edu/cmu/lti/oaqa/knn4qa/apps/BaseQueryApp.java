/*
 *  Copyright 2015 Carnegie Mellon University
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package edu.cmu.lti.oaqa.knn4qa.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.*;

import javax.annotation.Nullable;

import no.uib.cipr.matrix.DenseVector;

import org.apache.commons.cli.*;
import org.apache.commons.math3.stat.descriptive.SynchronizedSummaryStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;

import edu.cmu.lti.oaqa.annographix.util.CompressUtils;
import edu.cmu.lti.oaqa.annographix.util.XmlHelper;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.BruteForceKNNCandidateProvider;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.CandidateEntry;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.CandidateInfo;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.CandidateProvider;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.LuceneCandidateProvider;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.LuceneGIZACandidateProvider;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.NmslibKNNCandidateProvider;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.NmslibQueryGenerator;
import edu.cmu.lti.oaqa.knn4qa.cand_providers.SolrCandidateProvider;
import edu.cmu.lti.oaqa.knn4qa.letor.FeatureExtractor;
import edu.cmu.lti.oaqa.knn4qa.letor.InMemIndexFeatureExtractor;
import edu.cmu.lti.oaqa.knn4qa.utils.QrelReader;
import ciir.umass.edu.learning.*;


/**
 * This class converts a dense vector to DataPoint format of the RankLib library
 * version 2.5.
 * 
 * <p>Perhaps, an <b>important</b> note: a RankLib DataPoint class contains 
 * a static variable featureCount. It doesn't seem to be used except
 * for feature normalization or by the evaluator code. So, it seems
 * to be fine to leave this variable set to default value (zero).

 * </p>
 * 
 * @author Leonid Boytsov
 *
 */
class DataPointWrapper extends DataPoint {
  DataPointWrapper() {}
  
  void assign(DenseVector feat) {
    mFeatValues = new float[feat.size() + 1];
    double data[] = feat.getData();
    for (int i = 0; i < feat.size(); ++i)
      mFeatValues[i+1] = (float)data[i];
  }
  
  @Override
  public float getFeatureValue(int fid) {
    return mFeatValues[fid];
  }

  @Override
  public float[] getFeatureVector() {
    return mFeatValues;
  }

  @Override
  public void setFeatureValue(int fid, float val) {
    mFeatValues[fid] = val;
  }

  @Override
  public void setFeatureVector(float[] vals) {
    mFeatValues = vals;
  }
  
  float [] mFeatValues;
}

class BaseQueryAppProcessingThread extends Thread {
  public static Object            mWriteLock = new Object();
  private final int               mThreadId;
  private final int               mThreadQty;
  private final BaseQueryApp      mAppRef;
  
  BaseQueryAppProcessingThread(BaseQueryApp appRef,
                               int          threadId,
                               int          threadQty) {
    mAppRef    = appRef;
    mThreadId  = threadId;
    mThreadQty = threadQty;    
  }
  
  @Override
  public void run() {
    
    try {
      mAppRef.logger.info("Thread id=" + mThreadId + " is created, the total # of threads " + mThreadQty);
      
      CandidateProvider candProvider = mAppRef.mCandProviders[mThreadId];
      
      boolean addRankScores = mAppRef.mInMemExtrFinal != null && mAppRef.mInMemExtrFinal.addRankScores();
      
      for (int iq = 0; iq < mAppRef.mQueries.size(); ++iq)
      if (iq % mThreadQty == mThreadId) {
        Map<String, String>    docFields = null;
        String                 docText = mAppRef.mQueries.get(iq);
        
        // 1. Parse a query
        try {
          docFields = XmlHelper.parseXMLIndexEntry(docText);
        } catch (Exception e) {
          mAppRef.logger.error("Parsing error, offending DOC:\n" + docText);
          throw new Exception("Parsing error.");
        }
        
        String queryID = docFields.get(CandidateProvider.ID_FIELD_NAME);
                
        // 2. Obtain results
        long start = System.currentTimeMillis();
        
        CandidateInfo qres = candProvider.getCandidates(iq, docFields, mAppRef.mMaxCandRet);
        CandidateEntry [] resultsAll = qres.mEntries;
        
        long end = System.currentTimeMillis();
        long searchTimeMS = end - start;
        
        mAppRef.logger.info(
            String.format("Obtained results for the query # %d queryId='%s' thread ID=%d, the search took %d ms, we asked for max %d entries got %d", 
                          iq, queryID, mThreadId, searchTimeMS, mAppRef.mMaxCandRet, resultsAll.length));
        
        mAppRef.mQueryTimeStat.addValue(searchTimeMS);
        mAppRef.mNumRetStat.addValue(qres.mNumFound);
        
        ArrayList<String>           allDocIds = new ArrayList<String>();
                        
        for (int rank = 0; rank < resultsAll.length; ++rank) {
          CandidateEntry e = resultsAll[rank];
          allDocIds.add(e.mDocId);
          if (addRankScores) e.mOrigRank = rank;
        }
        
        // allDocFeats will be first created by an intermediate re-ranker (if it exists).
        // If there is a final re-ranker, it will overwrite previously created features.
        Map<String, DenseVector> allDocFeats = null;
        Integer maxNumRet = mAppRef.mMaxNumRet;
                
        // 3. If necessary carry out an intermediate re-ranking
        if (mAppRef.mInMemExtrInterm != null) {
          // Compute features once for all documents using an intermediate re-ranker
          start = System.currentTimeMillis();
          allDocFeats = mAppRef.mInMemExtrInterm.getFeatures(allDocIds, docFields);
          
          DenseVector intermModelWeights = mAppRef.mModelInterm;

          for (int rank = 0; rank < resultsAll.length; ++rank) {
            CandidateEntry e = resultsAll[rank];
            DenseVector feat = allDocFeats.get(e.mDocId);
            e.mScore = (float) feat.dot(intermModelWeights);
            if (Float.isNaN(e.mScore)) {
              if (Float.isNaN(e.mScore)) {
                mAppRef.logger.info("DocId=" + e.mDocId + " queryId=" + queryID);
                mAppRef.logger.info("NAN scores, feature vector:");
                mAppRef.logger.info(feat.toString());
                mAppRef.logger.info("NAN scores, feature weights:");
                mAppRef.logger.info(intermModelWeights.toString());
                throw new Exception("NAN score encountered (intermediate reranker)!");
              }
            }
          }
          Arrays.sort(resultsAll);
          // We may now need to update allDocIds and resultsAll to include only top-maxNumRet entries!
          if (resultsAll.length > maxNumRet) {
            allDocIds = new ArrayList<String>();
            CandidateEntry resultsAllTrunc[] = Arrays.copyOf(resultsAll, maxNumRet);
            resultsAll = resultsAllTrunc;
            for (int rank = 0; rank < resultsAll.length; ++rank) 
              allDocIds.add(resultsAll[rank].mDocId);            
          }
          end = System.currentTimeMillis();
          long rerankIntermTimeMS = end - start;
          mAppRef.logger.info(
              String.format("Intermediate-feature generation & re-ranking for the query # %d queryId='%s' thread ID=%d, took %d ms", 
                             iq, queryID, mThreadId, rerankIntermTimeMS));
          mAppRef.mIntermRerankTimeStat.addValue(rerankIntermTimeMS);          
        }
                
        // 4. If QRELs are specified, we need to save results only for subsets that return a relevant entry. 
        //    Let's see what's the rank of the highest ranked entry. 
        //    If, e.g., the rank is 10, then we discard subsets having less than top-10 entries.
        int minRelevRank = Integer.MAX_VALUE;
        if (mAppRef.mQrels != null) {
          for (int rank = 0; rank < resultsAll.length; ++rank) {
            CandidateEntry e = resultsAll[rank];
            String label = mAppRef.mQrels.get(queryID, e.mDocId);
            if (CandidateProvider.isRelevLabel(label, 1)) {
              e.mIsRelev = true;
              minRelevRank = rank;
              break;
            }
          }
        } else {
          minRelevRank = 0;
        }
        
        // 5. If the final re-ranking model is specified, let's re-rank again and save all the results
        if (mAppRef.mInMemExtrFinal!= null) {
          if (allDocIds.size() > maxNumRet) {
            throw new RuntimeException("Bug: allDocIds.size()=" + allDocIds.size() + " > maxNumRet=" + maxNumRet);
          }
          // Compute features once for all documents using a final re-ranker
          start = System.currentTimeMillis();
          allDocFeats = mAppRef.mInMemExtrFinal.getFeatures(allDocIds, docFields);
          if (addRankScores) {
            addScoresAndRanks(allDocFeats, resultsAll);
          }
          
          Ranker modelFinal = mAppRef.mModelFinal;
          
          if (modelFinal != null) {
            DataPointWrapper featRankLib = new DataPointWrapper();
            for (int rank = 0; rank < resultsAll.length; ++rank) {
              CandidateEntry e = resultsAll[rank];
              DenseVector feat = allDocFeats.get(e.mDocId);
              // It looks like eval is thread safe in RankLib 2.5.
              featRankLib.assign(feat);                            
              e.mScore = (float) modelFinal.eval(featRankLib);
              if (Float.isNaN(e.mScore)) {
                if (Float.isNaN(e.mScore)) {
                  mAppRef.logger.info("DocId=" + e.mDocId + " queryId=" + queryID);
                  mAppRef.logger.info("NAN scores, feature vector:");
                  mAppRef.logger.info(feat.toString());
                  throw new Exception("NAN score encountered (intermediate reranker)!");
                }
              }
            }            
          }          
          
          end = System.currentTimeMillis();
          long rerankFinalTimeMS = end - start;
          mAppRef.logger.info(
              String.format("Final-feature generation & re-ranking for the query # %d queryId='%s' thread ID=%d, final. reranking took %d ms", 
                            iq, queryID, mThreadId, rerankFinalTimeMS));
          mAppRef.mFinalRerankTimeStat.addValue(rerankFinalTimeMS);                        
        }
        
        /* 
         * After computing scores based on the final model, elements need to be resorted.
         * However, this needs to be done *SEPARATELY* for each of the subset of top-K results.
         */
        

        for (int k = 0; k < mAppRef.mNumRetArr.size(); ++k) {
          int numRet = mAppRef.mNumRetArr.get(k);
          if (numRet >= minRelevRank) {
            CandidateEntry resultsCurr[] = Arrays.copyOf(resultsAll, Math.min(numRet, resultsAll.length));
            Arrays.sort(resultsCurr);
            synchronized (mWriteLock) {
              mAppRef.procResults(
                  queryID,
                  docFields,
                  resultsCurr,
                  numRet,
                  allDocFeats
               );
            }
          }
        }                
      }

      
      mAppRef.logger.info("Thread id=" + mThreadId + " finished!");
    } catch (Exception e) {
      e.printStackTrace();
      System.err.println("Unhandled exception: " + e + " ... exiting");
      System.exit(1);
    }
  }

  /**
   * Adds ranks and scores obtained from a candidate provider.
   * 
   * @param docFeats        all features
   * @param resultsAll      result entries
   */
  private void addScoresAndRanks(Map<String, DenseVector>   docFeats, 
                                 CandidateEntry[]           resultsAll) {
    for (CandidateEntry  e: resultsAll) {
      DenseVector oldVect = docFeats.get(e.mDocId);
      int oldSize = oldVect.size();
      DenseVector newVect = new DenseVector(oldSize + 2);
      newVect.set(0, e.mOrigRank);
      newVect.set(1, e.mOrigScore);
      for (int vi = 0; vi < oldSize; ++vi)
        newVect.set(vi + 2, oldVect.get(vi)); 
      docFeats.replace(e.mDocId, newVect);
    }    
    
  }
      
}

/**
 * This class provides a basic functionality related to
 * reading parameters, initializing shared resources, creating
 * result providers, etc; It has abstract function(s) that are
 * supposed to implemented in child classes.
 * 
 * @author Leonid Boytsov
 *
 */
public abstract class BaseQueryApp {
  
  /**
   * A child class implements this function where it calls {@link #addCandGenOpts(boolean, boolean, boolean)},
   * {@link #addResourceOpts(boolean)}, and {@link #addLetorOpts(boolean, boolean)} with appropriate parameters,
   * as well as adds custom options.
   * 
   */
  abstract void addOptions();
  
  /**
   * A child class implements this function to read values of custom options.
   */
  abstract void procCustomOptions();
  
  /**
   * A child class initializes, e.g., output file(s) in this function.
   * @throws Exception 
   */
  abstract void init() throws Exception;
  /**
   * A child class finializes execution, e.g., closes output files(s) in this function.
   * @throws Exception 
   */
  abstract void fin() throws Exception;
  /**
   * A child class will process results in this function, e.g, it will save them to the output file;
   * The procRecults() function needn't be synchronized</b>, class {@link BaseQueryAppProcessingThread} 
   * will take care to sync. 
   * @param docFields 
   * 
   * @param   queryId
   *              a query ID
   * @param   docFields
   *              a map, where keys are field names, while values represent
   *              values of indexable fields.
   * @param   scoredDocs
   *              a list of scored document entries
   * @param   numRet
   *              The result set will be generated for this number of records.
   * @param   docFeats
   *              a list of document entry features (may be NULL)
   *      
   */
  abstract void procResults(
      String                              queryID,
      Map<String, String>                 docFields, 
      CandidateEntry[]                    scoredDocs,
      int                                 numRet,
      @Nullable Map<String, DenseVector>  docFeats
      ) throws Exception;
    
  
  final Logger logger = LoggerFactory.getLogger(BaseQueryApp.class);
  
  void showUsage(String err) {
    System.err.println("Error: " + err);
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp(mAppName, mOptions);      
    System.exit(1);
  }
  void showUsageSpecify(String optName) {
    showUsage("Specify: '" + optName + "'");
  }  

  
  /**
   * Adds options related to candidate generation.  
   * 
   * @param onlyLucene
   *                    if true, we don't allow to specify the provider type and only uses Lucene
   * @param multNumRetr
   *                    if true, an app generates results for top-K sets of various sizes
   * @param useQRELs
   *                    if true, an app uses QRELs
   * @param useThreadQty
   *                    if true, an app allows to specify the number of threads.                     
   */
  void addCandGenOpts(boolean onlyLucene,
                      boolean multNumRetr,
                      boolean useQRELs,
                      boolean useThreadQty) {
    mMultNumRetr = multNumRetr;
    mOnlyLucene = onlyLucene;

    if (onlyLucene) {
      mOptions.addOption(CommonParams.PROVIDER_URI_PARAM,      null, true, CommonParams.LUCENE_INDEX_LOCATION_DESC);
    } else {
      mOptions.addOption(CommonParams.CAND_PROVID_PARAM,       null, true, CandidateProvider.CAND_PROVID_DESC);
      mOptions.addOption(CommonParams.PROVIDER_URI_PARAM,      null, true, CommonParams.PROVIDER_URI_DESC);
      mOptions.addOption(CommonParams.MIN_SHOULD_MATCH_PCT_PARAM, null, true, CommonParams.MIN_SHOULD_MATCH_PCT_DESC);
    }
    mOptions.addOption(CommonParams.QUERY_FILE_PARAM,          null, true, CommonParams.QUERY_FILE_DESC);
    mOptions.addOption(CommonParams.MAX_NUM_RESULTS_PARAM,     null, true, mMultNumRetr ? 
                                                                             CommonParams.MAX_NUM_RESULTS_DESC : 
                                                                             "A number of candidate records (per-query)");
    mOptions.addOption(CommonParams.MAX_NUM_QUERY_PARAM,       null, true, CommonParams.MAX_NUM_QUERY_DESC);

    mUseQRELs = useQRELs;
    if (mUseQRELs)
      mOptions.addOption(CommonParams.QREL_FILE_PARAM,         null, true, CommonParams.QREL_FILE_DESC);

    if (useThreadQty)
      mOptions.addOption(CommonParams.THREAD_QTY_PARAM,        null, true, CommonParams.THREAD_QTY_DESC);
    mOptions.addOption(CommonParams.KNN_THREAD_QTY_PARAM,      null, true, CommonParams.KNN_THREAD_QTY_DESC);
    mOptions.addOption(CommonParams.KNN_WEIGHTS_FILE_PARAM,    null, true, CommonParams.KNN_WEIGHTS_FILE_DESC);
    mOptions.addOption(CommonParams.MAX_NUM_QUERY_PARAM,       null, true, CommonParams.MAX_NUM_QUERY_DESC);
    
    mOptions.addOption(CommonParams.GIZA_EXPAND_QTY_PARAM,          null, true,  CommonParams.GIZA_EXPAND_QTY_DESC);
    mOptions.addOption(CommonParams.GIZA_EXPAND_USE_WEIGHTS_PARAM,  null, false, CommonParams.GIZA_EXPAND_USE_WEIGHTS_DESC);

    mOptions.addOption(CommonParams.NMSLIB_FIELDS_PARAM,       null, true, CommonParams.NMSLIB_FIELDS_DESC);
    
    mOptions.addOption(CommonParams.SAVE_STAT_FILE_PARAM,      null, true, CommonParams.SAVE_STAT_FILE_DESC);
  }
  
  /**
   * Adds options related to resource initialization.
   * 
   * @param useHigHorderModels
   *            if true, high-order models are used
   */
  void addResourceOpts(boolean useHigHorderModels) {    
    mOptions.addOption(CommonParams.MEMINDEX_PARAM,            null, true,  CommonParams.MEMINDEX_DESC);    
    mOptions.addOption(CommonParams.GIZA_ROOT_DIR_PARAM,       null, true,  CommonParams.GIZA_ROOT_DIR_DESC);
    mOptions.addOption(CommonParams.GIZA_ITER_QTY_PARAM,       null, true,  CommonParams.GIZA_ITER_QTY_DESC);   
    mOptions.addOption(CommonParams.EMBED_DIR_PARAM,           null, true,  CommonParams.EMBED_DIR_DESC);
    mOptions.addOption(CommonParams.EMBED_FILES_PARAM,         null, true,  CommonParams.EMBED_FILES_DESC);
    if (useHigHorderModels)
      mOptions.addOption(CommonParams.HIHG_ORDER_FILES_PARAM,    null, true,  CommonParams.HIHG_ORDER_FILES_DESC);            
  }
  
  /**
   * Adds options related to LETOR (learning-to-rank).
   */
  void addLetorOpts(boolean useIntermModel, boolean useFinalModel) {
    mOptions.addOption(CommonParams.EXTRACTOR_TYPE_FINAL_PARAM,     null, true,  CommonParams.EXTRACTOR_TYPE_FINAL_DESC);
    mOptions.addOption(CommonParams.EXTRACTOR_TYPE_INTERM_PARAM,    null, true,  CommonParams.EXTRACTOR_TYPE_INTERM_DESC);
    mUseIntermModel = useIntermModel;
    mUseFinalModel = useFinalModel;
    if (mUseIntermModel) {
      mOptions.addOption(CommonParams.MODEL_FILE_INTERM_PARAM, null, true, CommonParams.MODEL_FILE_INTERM_DESC);
      mOptions.addOption(CommonParams.MAX_CAND_QTY_PARAM,      null, true, CommonParams.MAX_CAND_QTY_DESC);
    }
    if (mUseFinalModel) {
      mOptions.addOption(CommonParams.MODEL_FILE_FINAL_PARAM,  null, true, CommonParams.MODEL_FILE_FINAL_DESC);
    }
  }
  
  /**
   * Parses arguments and reads specified options (additional options can be read by subclasses)
   * @throws Exception 
   */
  void parseAndReadOpts(String args[]) throws Exception {
    mCmd = mParser.parse(mOptions, args);

    if (mOnlyLucene)
      mCandProviderType = CandidateProvider.CAND_TYPE_LUCENE;
    else { 
      mCandProviderType = mCmd.getOptionValue(CommonParams.CAND_PROVID_PARAM);
      if (null == mCandProviderType) showUsageSpecify(CommonParams.CAND_PROVID_DESC);
    }
    
    String tmps = mCmd.getOptionValue(CommonParams.NMSLIB_FIELDS_PARAM);
    if (null != tmps) {
      mNmslibFields = tmps.split(",");
    }
    mProviderURI = mCmd.getOptionValue(CommonParams.PROVIDER_URI_PARAM);
    if (null == mProviderURI) showUsageSpecify(CommonParams.PROVIDER_URI_DESC);              
    mQueryFile = mCmd.getOptionValue(CommonParams.QUERY_FILE_PARAM);
    if (null == mQueryFile) showUsageSpecify(CommonParams.QUERY_FILE_DESC);
    String tmpn = mCmd.getOptionValue(CommonParams.MAX_NUM_QUERY_PARAM);
    if (tmpn != null) {
      try {
        mMaxNumQuery = Integer.parseInt(tmpn);
      } catch (NumberFormatException e) {
        showUsage("Maximum number of queries isn't integer: '" + tmpn + "'");
      }
    }
    tmpn = mCmd.getOptionValue(CommonParams.MAX_NUM_RESULTS_PARAM);
    if (null == tmpn) showUsageSpecify(CommonParams.MAX_NUM_RESULTS_DESC);
    
    logger.info(String.format("Candidate provider type: %s URI: %s Query file: %s Maximum # of queries: %d # of cand. records: %s", 
        mCandProviderType, mProviderURI, mQueryFile, mMaxNumQuery, tmpn));
    
    // mMaxNumRet must be init before mMaxCandRet
    mMaxNumRet = Integer.MIN_VALUE;
    for (String s: mSplitOnComma.split(tmpn)) {
      int n = 0;
      
      try {
        n = Integer.parseInt(s);
      } catch (NumberFormatException e) {
        showUsage("Number of candidates isn't integer: '" + s + "'");
      }

      if (n <= 0) {
        showUsage("Specify only positive number of candidate entries");
      }
      
      mNumRetArr.add(n);      
      mMaxNumRet = Math.max(n, mMaxNumRet);
    }
    mMaxCandRet = mMaxNumRet; // if the user doesn't specify the # of candidates, it's set to the maximum # of answers to produce
    
    mQrelFile = mCmd.getOptionValue(CommonParams.QREL_FILE_PARAM);
    if (mQrelFile != null) {
      logger.info("QREL-file: " + mQrelFile);
      mQrels = new QrelReader(mQrelFile);
    }
    tmpn = mCmd.getOptionValue(CommonParams.THREAD_QTY_PARAM);
    if (null != tmpn) {
      try {
        mThreadQty = Integer.parseInt(tmpn);
      } catch (NumberFormatException e) {
        showUsage("Number of threads isn't integer: '" + tmpn + "'");
      }
    }
    logger.info(String.format("Number of threads: %d", mThreadQty));
    tmpn = mCmd.getOptionValue(CommonParams.KNN_THREAD_QTY_PARAM);
    if (null != tmpn) {
      try {
        mKnnThreadQty = Integer.parseInt(tmpn);
      } catch (NumberFormatException e) {
        showUsage("Number of threads for brute-force KNN-provider isn't integer: '" + tmpn + "'");
      }
    }
    String knnWeightFileName = mCmd.getOptionValue(CommonParams.KNN_WEIGHTS_FILE_PARAM);
    if (null != knnWeightFileName) {
      mKnnWeights = FeatureExtractor.readFeatureWeights(knnWeightFileName);
    }
    tmpn = mCmd.getOptionValue(CommonParams.GIZA_EXPAND_QTY_PARAM);
    if (tmpn != null) {
      try {
        mGizaExpandQty = Integer.parseInt(tmpn);
      } catch (NumberFormatException e) {
        showUsage("Number of GIZA-based query-extension terms is not integer: '" + tmpn + "'");
      }
    }
    mGizaExpandUseWeights = mCmd.hasOption(CommonParams.GIZA_EXPAND_USE_WEIGHTS_PARAM);
    mGizaRootDir = mCmd.getOptionValue(CommonParams.GIZA_ROOT_DIR_PARAM);
    tmpn = mCmd.getOptionValue(CommonParams.GIZA_ITER_QTY_PARAM);
    if (null != tmpn) {
      try {
        mGizaIterQty = Integer.parseInt(tmpn);
      } catch (NumberFormatException e) {
        showUsage("Number of GIZA iterations isn't integer: '" + tmpn + "'");
      }
    }
    mEmbedDir = mCmd.getOptionValue(CommonParams.EMBED_DIR_PARAM);
    String embedFilesStr = mCmd.getOptionValue(CommonParams.EMBED_FILES_PARAM);

    if (null != embedFilesStr) {
      mEmbedFiles = embedFilesStr.split(",");
    }

    String highOrderFilesStr = mCmd.getOptionValue(CommonParams.HIHG_ORDER_FILES_PARAM);
    if (null != highOrderFilesStr) {
      mHighOrderFiles = highOrderFilesStr.split(",");
    }
    mMemIndexPref = mCmd.getOptionValue(CommonParams.MEMINDEX_PARAM);
    mExtrTypeInterm = mCmd.getOptionValue(CommonParams.EXTRACTOR_TYPE_INTERM_PARAM);
    if (mExtrTypeInterm != null) {
      String modelFile = mCmd.getOptionValue(CommonParams.MODEL_FILE_INTERM_PARAM);
      if (null == modelFile) 
        showUsageSpecify(CommonParams.MODEL_FILE_INTERM_PARAM);
      mMaxCandRet = mMaxNumRet; // if the user doesn't specify the # of candidates, it's set to the maximum # of answers to produce
      mModelInterm = FeatureExtractor.readFeatureWeights(modelFile);
      tmpn = mCmd.getOptionValue(CommonParams.MAX_CAND_QTY_PARAM);
      if (null == tmpn)
        showUsageSpecify(CommonParams.MAX_CAND_QTY_DESC);
      try {
        mMaxCandRet = Integer.parseInt(tmpn);
        if (mMaxCandRet < mMaxNumRet)
          mMaxCandRet = mMaxNumRet; // The number of candidate records can't be < the the # of records we need to retrieve
      } catch (NumberFormatException e) {
        showUsage("The value of '" + CommonParams.MAX_CAND_QTY_DESC + "' isn't integer: '" + tmpn + "'");
      }

      logger.info("Using the following weights for the intermediate re-ranker:");
      logger.info(mModelInterm.toString());
    }
    mExtrTypeFinal = mCmd.getOptionValue(CommonParams.EXTRACTOR_TYPE_FINAL_PARAM);
    if (mExtrTypeFinal != null) {
      if (mUseFinalModel) {
        String modelFile = mCmd.getOptionValue(CommonParams.MODEL_FILE_FINAL_PARAM);
        if (null == modelFile) 
          showUsageSpecify(CommonParams.MODEL_FILE_FINAL_PARAM);
        RankerFactory rf = new RankerFactory();
        mModelFinal = rf.loadRankerFromFile(modelFile);
        logger.info("Loaded the final-stage model from the following file: '" + modelFile + "'");
      }
    }
    mSaveStatFile = mCmd.getOptionValue(CommonParams.SAVE_STAT_FILE_PARAM);
    if (mSaveStatFile != null)
      logger.info("Saving some vital stats to '" + mSaveStatFile);
    
    tmpn = mCmd.getOptionValue(CommonParams.MIN_SHOULD_MATCH_PCT_PARAM);
    if (null != tmpn) {
      try {
        mMinShouldMatchPCT = Integer.parseInt(tmpn);
      } catch (NumberFormatException e) {
        showUsage("The percentage of query word to match isn't integer: '" + tmpn + "'");
      }
      if (mMinShouldMatchPCT <0 || mMinShouldMatchPCT >= 100)
        showUsage("The percentage of query word to match isn't an integer from 0 to 100: '" + tmpn + "'");
    }

  }
  
  /**
   * This function initializes feature extractors.
   * @throws Exception 
   */
  void initExtractors() throws Exception {
    if (mExtrTypeFinal != null)
      mInMemExtrFinal  = createOneExtractor(mExtrTypeFinal);
    if (mExtrTypeInterm != null)
      mInMemExtrInterm = createOneExtractor(mExtrTypeInterm, mInMemExtrFinal /* try to reuse existing resources from another extractor */);
  }
  
  /**
   * This function initializes the provider, it should be called after {@link #initExtractors()}.
   * 
   * <b>Providers will try re-use resources created by extractors. Hence, they should be initialized
   * after extractors.</b>
   * 
   * @throws Exception
   */
  void initProvider() throws Exception {
    mCandProviders = new CandidateProvider[mThreadQty];
    
    if (mCandProviderType.equalsIgnoreCase(CandidateProvider.CAND_TYPE_SOLR)) {
      mCandProviders[0] = new SolrCandidateProvider(mProviderURI, mMinShouldMatchPCT);
      for (int ic = 1; ic < mThreadQty; ++ic) 
        mCandProviders[ic] = mCandProviders[0];
    } else if (mCandProviderType.equalsIgnoreCase(CandidateProvider.CAND_TYPE_LUCENE)) {
      mCandProviders[0] = new LuceneCandidateProvider(mProviderURI);
      for (int ic = 1; ic < mThreadQty; ++ic) 
        mCandProviders[ic] = mCandProviders[0];
    } else if (mCandProviderType.equalsIgnoreCase(CandidateProvider.CAND_TYPE_LUCENE_GIZA)) {
      if (mGizaExpandQty == null)
        showUsageSpecify(CommonParams.GIZA_EXPAND_QTY_DESC);
      if (mGizaRootDir == null) {
        showUsageSpecify(CommonParams.GIZA_ROOT_DIR_DESC);
      }
      if (mGizaIterQty <= 0) {
        showUsageSpecify(CommonParams.GIZA_ITER_QTY_DESC);
      }
      
      mCandProviders[0] = new LuceneGIZACandidateProvider(mProviderURI, mGizaExpandQty, mGizaExpandUseWeights,
                                                          mGizaRootDir, mGizaIterQty, 
                                                          mMemIndexPref,
                                                          mInMemExtrFinal, mInMemExtrInterm);
      for (int ic = 1; ic < mThreadQty; ++ic) 
        mCandProviders[ic] = mCandProviders[0];        
    } else if (mCandProviderType.equalsIgnoreCase(CandidateProvider.CAND_TYPE_KNN)) {
      if (null != mInMemExtrInterm)
        showUsage("One shouldn't use an intermeditate re-ranker together with the brute-force Java provider!");
      if (null == mKnnWeights)
        showUsageSpecify(CommonParams.KNN_WEIGHTS_FILE_DESC);
      if (null == mInMemExtrInterm)
        showUsageSpecify(CommonParams.EXTRACTOR_TYPE_FINAL_DESC);
      mCandProviders[0] = new BruteForceKNNCandidateProvider(mInMemExtrFinal,
                                                             mKnnWeights,
                                                             mKnnThreadQty
                                                            );      
      for (int ic = 1; ic < mThreadQty; ++ic) 
        mCandProviders[ic] = mCandProviders[0];        
    } else if (mCandProviderType.equals(CandidateProvider.CAND_TYPE_NMSLIB)) {
      /*
       * NmslibKNNCandidateProvider isn't really thread-safe,
       * b/c each instance creates a TCP/IP that isn't supposed to be shared among threads.
       * However, creating one instance of the provider class per thread is totally fine (and is the right way to go). 
       */
      if (null == mNmslibFields) showUsageSpecify(CommonParams.NMSLIB_FIELDS_PARAM);
      NmslibQueryGenerator queryGen = 
          new NmslibQueryGenerator(mNmslibFields, mMemIndexPref, mInMemExtrInterm, mInMemExtrFinal); 
      for (int ic = 0; ic < mThreadQty; ++ic) {
        mCandProviders[ic] = new NmslibKNNCandidateProvider(mProviderURI, queryGen);
      }                
    } else {
      showUsage("Wrong candidate record provider type: '" + mCandProviderType + "'");
    }

  }
  
  /**
   * Creates one in-memory feature extractor.
   * 
   * @param extrType            
   *              an extractor type
   * @param donnorExtractors
   *              "donnor" extractor, they permit sharing their resources
   * @return
   * @throws Exception
   */
  InMemIndexFeatureExtractor createOneExtractor(String extrType, InMemIndexFeatureExtractor... donnorExtractors)
      throws Exception {
    if (null == mMemIndexPref)
      showUsageSpecify(CommonParams.MEMINDEX_DESC);

    InMemIndexFeatureExtractor inMemExtractor = 
        InMemIndexFeatureExtractor.createExtractor(extrType, 
                                                   mGizaRootDir, mGizaIterQty, 
                                                   mMemIndexPref, 
                                                   mEmbedDir, mEmbedFiles, mHighOrderFiles);

    if (inMemExtractor == null) {
      showUsage("Wrong type of the feature extractor: '" + extrType + "'");
    }

    if (inMemExtractor.needsSomeEmbed()) {
      if (null == mEmbedDir) {
        showUsageSpecify(CommonParams.EMBED_DIR_DESC);
      }
    }
    if (inMemExtractor.needsDenseEmbed()) {
      if (null == mEmbedFiles || mEmbedFiles.length == 0) {
        showUsageSpecify(CommonParams.EMBED_FILES_DESC);
      }
    }
    if (inMemExtractor.needsHighOrderEmbed()) {
      if (null == mHighOrderFiles || mHighOrderFiles.length == 0) {
        showUsageSpecify(CommonParams.HIHG_ORDER_FILES_DESC);
      }
    }
    if (inMemExtractor.needsGIZA()) {
      if (mGizaRootDir == null) {
        showUsageSpecify(CommonParams.GIZA_ROOT_DIR_DESC);
      }
      if (mGizaIterQty <= 0) {
        showUsageSpecify(CommonParams.GIZA_ITER_QTY_DESC);
      }
    }

    inMemExtractor.init(donnorExtractors);

    return inMemExtractor;
  }
  
  /**
   * For debugging only.
   */
  static void printFloatArr(float a[]) {
    for (float v: a)  System.out.print(v + " ");
    System.out.println();
  }
  
  void run(String appName, String args[]) throws Exception {
    mAppName = appName;
    logger.info(appName + " started");
    
    try {
      long start = System.currentTimeMillis();
      
      // Read options
      addOptions();
      parseAndReadOpts(args);
      procCustomOptions();
      // Init. resources
      initExtractors();
      // Note that providers must be initialized after resources and extractors are initialized
      // because they may some of the resources (e.g., NMSLIB needs an in-memory feature extractor)
      initProvider();
      
      BufferedReader  inpText = new BufferedReader(new InputStreamReader(CompressUtils.createInputStream(mQueryFile)));
      
      String docText = XmlHelper.readNextXMLIndexEntry(inpText);        
      int docQty = 0;
      for (; docText!= null && docQty < mMaxNumQuery; 
          docText = XmlHelper.readNextXMLIndexEntry(inpText)) {
        
        mQueries.add(docText);
        ++docQty;
        if (docQty % 100 == 0) logger.info("Read " + docQty + " documents");
      }
      
      logger.info("Read " + docQty + " documents"); 
      
      init();
      
      BaseQueryAppProcessingThread[] workers = new BaseQueryAppProcessingThread[mThreadQty];
      
      for (int threadId = 0; threadId < mThreadQty; ++threadId) {               
        workers[threadId] = new BaseQueryAppProcessingThread(this, threadId, mThreadQty);
      }
            
      // Start threads
      for (BaseQueryAppProcessingThread e : workers) e.start();
      // Wait till they finish
      for (BaseQueryAppProcessingThread e : workers) e.join(0);
     
      fin();
      
      long end = System.currentTimeMillis();
      double totalTimeMS = end - start;
      
      double queryTime = mQueryTimeStat.getMean(), intermRerankTime = 0, finalRerankTime = 0;
      
      logger.info(String.format("Query time (ms):             mean=%f std=%f", 
                                mQueryTimeStat.getMean(), mQueryTimeStat.getStandardDeviation()));
      logger.info(String.format("Number of entries found:     mean=%f std=%f",
          mNumRetStat.getMean(), mNumRetStat.getStandardDeviation()));

      if (mModelInterm != null) {
        logger.info(String.format("Interm. reranking time (ms): mean=%f std=%f", 
              mIntermRerankTimeStat.getMean(), mIntermRerankTimeStat.getStandardDeviation()));
        intermRerankTime = mIntermRerankTimeStat.getMean();
      }
      if (mModelFinal != null) {
        logger.info(String.format("Final  reranking time (ms): mean=%f std=%f", 
              mFinalRerankTimeStat.getMean(), mFinalRerankTimeStat.getStandardDeviation()));
        finalRerankTime = mFinalRerankTimeStat.getMean();
      }
      
      if (mSaveStatFile != null) {
        FileWriter f = new FileWriter(new File(mSaveStatFile));
        f.write("QueryTime\tIntermRerankTime\tFinalRerankTime\tTotalTime\n");
        f.write(String.format("%f\t%f\t%f\t%f\n", queryTime, intermRerankTime, finalRerankTime, totalTimeMS));
        f.close();
      }
      
    } catch (ParseException e) {
      showUsageSpecify("Cannot parse arguments: " + e);
    } catch(Exception e) {
      e.printStackTrace();
      logger.error("Terminating due to an exception: " + e);
      System.exit(1);
    } 
    
    logger.info("Finished successfully!");
  }
  

  String       mProviderURI;
  String       mQueryFile;
  Integer      mMaxCandRet;
  Integer      mMaxNumRet;
  int          mMinShouldMatchPCT = 0;  
  int          mMaxNumQuery = Integer.MAX_VALUE;
  ArrayList<Integer> 
                     mNumRetArr= new ArrayList<Integer>();
  String       mCandProviderType;
  String       mQrelFile;
  QrelReader   mQrels;
  int          mThreadQty = 1;
  int          mKnnThreadQty = 1;
  String       mNmslibFields[];
  String       mSaveStatFile;
  DenseVector  mKnnWeights;        
  Integer      mGizaExpandQty;
  boolean      mGizaExpandUseWeights = false;
  String       mGizaRootDir;
  int          mGizaIterQty = -1;
  String       mEmbedDir;
  String       mEmbedFiles[];
  String       mHighOrderFiles[];
  String       mMemIndexPref;
  String       mExtrTypeFinal;
  String       mExtrTypeInterm;
  DenseVector  mModelInterm;
  Ranker       mModelFinal;
  
  InMemIndexFeatureExtractor mInMemExtrInterm;
  InMemIndexFeatureExtractor mInMemExtrFinal;
  
  String   mAppName;
  Options  mOptions = new Options();
  Splitter mSplitOnComma = Splitter.on(',');   
  
  CommandLineParser mParser = new org.apache.commons.cli.GnuParser();
  
  boolean  mMultNumRetr;    /** if true an application generate results for top-K sets of various sizes */
  boolean  mOnlyLucene;     /** if true, we don't allow to specify the provider type and only uses Lucene */
  boolean  mUseQRELs;       /** if true, a QREL file is read */
  boolean  mUseIntermModel; /** if true, an intermediate re-ranking model is used */
  boolean  mUseFinalModel;  /** if true, a final re-ranking model is used */
                                 
  CommandLine mCmd;
  
  CandidateProvider[] mCandProviders;
  
  SynchronizedSummaryStatistics mQueryTimeStat        = new SynchronizedSummaryStatistics();
  SynchronizedSummaryStatistics mIntermRerankTimeStat = new SynchronizedSummaryStatistics();
  SynchronizedSummaryStatistics mFinalRerankTimeStat  = new SynchronizedSummaryStatistics();
  SynchronizedSummaryStatistics mNumRetStat           = new SynchronizedSummaryStatistics();
  
  ArrayList<String>                               mQueries = new ArrayList<String>();      
}
