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
package edu.cmu.lti.oaqa.knn4qa.utils;

public abstract class VocabularyFilterAndRecoder extends VocabularyFilter {
  @Override
  public abstract boolean checkWord(String word);
  /**
   * @param word
   * @return        a "recoded" word identifier, or NULL, if the identifier can't be found
   */
  public abstract Integer getWordId(String word);
}
