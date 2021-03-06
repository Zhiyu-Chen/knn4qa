#!/bin/bash
DATA_ROOT=$1
if [ "$DATA_ROOT" = "" ] ; then
  echo "Specify the data directory root (1st arg)!"
  exit 1
fi

if [ ! -d "$DATA_ROOT" ] ; then
  echo "'$DATA_ROOT' is not a directory (1st arg)!"
  exit 1
fi

collect=$2
if [ "$collect" = "" ] ; then
  echo "Specify sub-collection (2d arg): manner, compr"
  exit 1
fi

part=$3
if [ "$part" = "" ] ; then
  echo "Specify sub-collection (3d arg): e.g., dev1, dev2, train1, ... "
  exit 1
fi

if [ "$collect" = "compr2M" ] ; then
  src_collect="compr"
else
  src_collect="$collect"
fi

max_num_query=$4
max_num_query_opt=""
if [ "$max_num_query" != "" ] ; then 
  echo "Using at most: $max_num_query queries (in case of sampling the size of the subset from which we sample)"
  max_num_query_opt=" -max_num_query $max_num_query"
fi

function check {
  f="$?"
  name=$1
  if [ "$f" != "0" ] ; then
    echo "**************************************"
    echo "* Failed: $name"
    echo "**************************************"
    exit 1
  fi
}

OUTPUT_DIR="$DATA_ROOT/nmslib/$collect/queries/$part/"

if [ ! -d "$OUTPUT_DIR" ] ; then
  mkdir -p "$OUTPUT_DIR"
  check "mkdir -p $OUTPUT_DIR"
fi

# Queries for each field
# Won't use most fields, b/c only two fields are really useful.
for field in text text_unlemm ; do
  cmd="scripts/nmslib/run_gen_nmslib_queries.sh -knn_queries $OUTPUT_DIR/${field}_queries.txt  -memindex_dir memfwdindex/$collect/ -q output/$src_collect/$part/SolrQuestionFile.txt -nmslib_fields $field $max_num_query_opt"
  bash -c "$cmd"
  check "$cmd"

  cmd="scripts/nmslib/run_gen_nmslib_queries.sh -knn_queries $OUTPUT_DIR/${field}_queries_sample0.25.txt  -memindex_dir memfwdindex/$collect/ -q output/$src_collect/$part/SolrQuestionFile.txt -nmslib_fields $field $max_num_query_opt -sel_prob 0.25"
  bash -c "$cmd"
  check "$cmd"
done

# Queries for 2 fields
cmd="scripts/nmslib/run_gen_nmslib_queries.sh -knn_queries $OUTPUT_DIR/2field_queries.txt  -memindex_dir memfwdindex/$collect/ -q output/$src_collect/$part/SolrQuestionFile.txt -nmslib_fields text,text_unlemm $max_num_query_opt"
bash -c "$cmd"
check "$cmd"

# Queries for 3 fields
cmd="scripts/nmslib/run_gen_nmslib_queries.sh -knn_queries $OUTPUT_DIR/3field_queries.txt  -memindex_dir memfwdindex/$collect/ -q output/$src_collect/$part/SolrQuestionFile.txt -nmslib_fields text,text_unlemm,bigram $max_num_query_opt"
bash -c "$cmd"

cmd="scripts/nmslib/run_gen_nmslib_queries.sh -knn_queries $OUTPUT_DIR/3field_queries_sample0.25.txt  -memindex_dir memfwdindex/$collect/ -q output/$src_collect/$part/SolrQuestionFile.txt -nmslib_fields text,text_unlemm,bigram $max_num_query_opt  -sel_prob 0.25"
bash -c "$cmd"
check "$cmd"
