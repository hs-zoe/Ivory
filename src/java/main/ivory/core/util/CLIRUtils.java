package ivory.core.util;

import ivory.core.data.dictionary.FrequencySortedDictionary;
import ivory.core.data.document.TermDocVector;
import ivory.core.data.stat.DfTableArray;
import ivory.core.data.stat.PrefixEncodedGlobalStats;
import ivory.core.tokenize.Tokenizer;
import ivory.pwsim.score.Bm25;
import ivory.pwsim.score.ScoringModel;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.umd.cloud9.io.map.HMapIFW;
import edu.umd.cloud9.io.map.HMapSFW;
import edu.umd.cloud9.io.map.HMapSIW;
import edu.umd.cloud9.io.pair.PairOfFloatString;
import edu.umd.cloud9.io.pair.PairOfIntFloat;
import edu.umd.cloud9.util.map.HMapIF;
import edu.umd.cloud9.util.map.MapKF.Entry;
import edu.umd.hooka.Vocab;
import edu.umd.hooka.VocabularyWritable;
import edu.umd.hooka.alignment.HadoopAlign;
import edu.umd.hooka.alignment.IndexedFloatArray;
import edu.umd.hooka.ttables.TTable_monolithic_IFAs;

/**
 * Algorithms used in our CLIR approach to convert doc vectors from one language into another. See SIGIR'11 paper for details.<p>
 * <p>
 * F is the "foreign" language, the language in which non-translated documents are written.<p>
 * E is the "non-foreign" language, the language into which documents are translated.<p>
 * <p>
 * Required files: <p>
 * 		ttable E-->F (i.e., Pr(f|e))<p>
 * 		ttable F-->E (i.e., Pr(e|f))<p>
 * 		Pair of vocabulary files for each ttable<p> 
 * 			V_E & V_F for E-->F<p>
 * 			V_E & V_F for F-->E<p>
 * 
 * @author ferhanture
 *
 */
public class CLIRUtils extends Configured {
  private static final Logger logger = Logger.getLogger(CLIRUtils.class);

  /**
   * Read df mapping from file.
   * 
   * @param path
   * 		path to df table
   * @param fs
   * 		FileSystem object
   * @return
   * 		mapping from term ids to df values
   */
  public static HMapIFW readTransDfTable(Path path, FileSystem fs) {
    HMapIFW transDfTable = new HMapIFW();
    try {
      SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, fs.getConf());

      IntWritable key = (IntWritable) reader.getKeyClass().newInstance();
      FloatWritable value = (FloatWritable) reader.getValueClass().newInstance();

      while (reader.next(key, value)) {
        transDfTable.put(key.get(), value.get());
        //				logger.info(key.get()+"-->"+value.get());
        key = (IntWritable) reader.getKeyClass().newInstance();
        value = (FloatWritable) reader.getValueClass().newInstance();
      }
      reader.close();
    } catch (Exception e) {
      throw new RuntimeException("Exception reading file trans-df table file");
    }
    return transDfTable;		
  }

  /**
   * @param vectorA
   * 		a term document vector
   * @param vectorB
   * 		another term document vector
   * @return
   * 		cosine score
   */
  public static float cosine(HMapIFW vectorA, HMapIFW vectorB) {
    float sum = 0, magA = 0, magB = 0;
    for(edu.umd.cloud9.util.map.MapIF.Entry e : vectorA.entrySet()){
      float value = e.getValue();
      magA += (value * value);
      if(vectorB.containsKey(e.getKey())){
        sum+= value*vectorB.get(e.getKey());
      }
    }
    for(edu.umd.cloud9.util.map.MapIF.Entry e : vectorB.entrySet()){
      float value = e.getValue();
      magB += (value * value);
    }
    if(magA==0 || magB==0){
      return 0.0f;
    }else{
      return (float) (sum/(Math.sqrt(magA) * Math.sqrt(magB)));
    }
  }

  /**
   * @param vectorA
   * 		a term document vector
   * @param vectorB
   * 		another term document vector
   * @return
   * 		cosine score
   */
  public static float cosine(HMapSFW vectorA, HMapSFW vectorB) {
    float sum = 0, magA = 0, magB = 0;
    for(edu.umd.cloud9.util.map.MapKF.Entry<String> e : vectorA.entrySet()){
      float value = e.getValue();
      magA += (value * value);
      if(vectorB.containsKey(e.getKey())){
        sum+= value*vectorB.get(e.getKey());
      }
    }
    for(edu.umd.cloud9.util.map.MapKF.Entry<String> e : vectorB.entrySet()){
      float value = e.getValue();
      magB += (value * value);
    }
    if(magA==0 || magB==0){
      return 0.0f;
    }else{
      return (float) (sum/(Math.sqrt(magA) * Math.sqrt(magB)));
    }
  }

  /**
   * 
   * @param vectorA
   * 		a normalized term document vector
   * @param vectorB
   * 		another normalized term document vector
   * @return
   * 		cosine score
   */
  public static float cosineNormalized(HMapSFW vectorA, HMapSFW vectorB) {
    float sum = 0;
    for(edu.umd.cloud9.util.map.MapKF.Entry<String> e : vectorA.entrySet()){
      float value = e.getValue();
      if(vectorB.containsKey(e.getKey())){
        sum+= value*vectorB.get(e.getKey());
      }
    }
    return sum;
  }

  public static float cosineNormalized2(HMapSFW vectorA, HMapSFW vectorB) {
    float sum = 0;
    for(edu.umd.cloud9.util.map.MapKF.Entry<String> e : vectorA.entrySet()){
      float value = e.getValue();
      if(vectorB.containsKey(e.getKey())){
        sum+= value*vectorB.get(e.getKey());
      }
    }
    return sum;
  }


  /**
   * Given a mapping from F-terms to their df values, compute a df value for each E-term using the CLIR algorithm: df(e) = sum_f{df(f)*prob(f|e)}
   * 
   * @param eVocabSrc
   * 		source-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param fVocabTrg
   * 		target-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param e2f_probs
   * 		ttable E-->F (i.e., Pr(f|e))
   * @param globalStatsMap
   * 		contains mapping from F-terms to their df values
   * @return
   * 		mapping from E-terms to their computed df values
   */
  public static HMapIFW translateDFTable(Vocab eVocabSrc, Vocab fVocabTrg, TTable_monolithic_IFAs e2f_probs, FrequencySortedDictionary dict, DfTableArray dfTable){
    HMapIFW transDfTable = new HMapIFW();
    for(int e=1;e<eVocabSrc.size();e++){
      int[] fS = e2f_probs.get(e).getTranslations(0.0f);
      float df=0;
      for(int f : fS){
        float probEF = e2f_probs.get(e, f);
        String fTerm = fVocabTrg.get(f);
        int id = dict.getId(fTerm); 
        if(id != -1){
          float df_f = dfTable.getDf(id);				
          df += (probEF*df_f);
        }else{
          logger.info(fTerm+" not in dict");
        }
      }
      transDfTable.put(e, df);
    }
    return transDfTable;
  }

  /**
   * Given a mapping from F-terms to their df values, compute a df value for each E-term using the CLIR algorithm: df(e) = sum_f{df(f)*prob(f|e)}
   * 
   * @param eVocabSrc
   * 		source-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param fVocabTrg
   * 		target-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param e2f_probs
   * 		ttable E-->F (i.e., Pr(f|e))
   * @param dfs
   * 		mapping from F-terms to their df values
   * @return
   * 		mapping from E-terms to their computed df values
   */
  public static HMapIFW translateDFTable(Vocab eVocabSrc, Vocab fVocabTrg, TTable_monolithic_IFAs e2f_probs, HMapSIW dfs){
    HMapIFW transDfTable = new HMapIFW();
    for(int e=1;e<eVocabSrc.size();e++){
      int[] fS = null;
      try {
        fS = e2f_probs.get(e).getTranslations(0.0f);
      } catch (Exception e1) {
        e1.printStackTrace();
      }
      float df=0;
      for(int f : fS){
        float probEF = e2f_probs.get(e, f);
        String fTerm = fVocabTrg.get(f);
        if(!dfs.containsKey(fTerm)){	//only if word is in the collection, can it contribute to the df values.
          continue;
        }			
        float df_f = dfs.get(fTerm);
        df+=(probEF*df_f);
      }
      transDfTable.put(e, df);
    }
    return transDfTable;
  }

  /**
   * Given a term in a document in F, and its tf value, update the computed tf value for each term in E using the CLIR algorithm: tf(e) = sum_f{tf(f)*prob(f|e)} <p>
   * Calling this method computes a single summand of the above equation.
   * 
   * @param fTerm
   *	 	term in a document in F
   * @param tf
   * 		term frequency of fTerm
   * @param tfTable
   * 		to be updated, a mapping from E-term ids to tf values
   * @param eVocabSrc
   * 		source-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param eVocabTrg
   * 		target-side vocabulary of the ttable F-->E (i.e., Pr(f|e))
   * @param fVocabSrc
   * 		source-side vocabulary of the ttable F-->E (i.e., Pr(e|f))
   * @param fVocabTrg
   * 		target-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param e2fProbs
   * 		ttable E-->F (i.e., Pr(f|e))
   * @param f2eProbs
   * 		ttable F-->E (i.e., Pr(e|f))
   * @param sLogger
   * 		Logger object for log output
   * @return
   * 		updated mapping from E-term ids to tf values
   * @throws IOException
   */
  public static HMapIFW updateTFsByTerm(String fTerm, int tf, HMapIFW tfTable, Vocab eVocabSrc, Vocab eVocabTrg, Vocab fVocabSrc, Vocab fVocabTrg, TTable_monolithic_IFAs e2fProbs, TTable_monolithic_IFAs f2eProbs, Logger sLogger){
    int f = fVocabSrc.get(fTerm);
    if(f <= 0){
      //			sLogger.warn(f+","+fTerm+" word not in aligner's vocab (foreign side of f2e)");
      return tfTable;
    }

    int[] eS = f2eProbs.get(f).getTranslations(0.0f);

    int f2 = fVocabTrg.get(fTerm);		//convert between two F vocabs  (different ids)
    if(f2 <= 0){
      //			sLogger.warn(fTerm+" word not in aligner's vocab (foreign side of e2f)");
      return tfTable;
    }
    //tf(e) = sum_f{tf(f)*prob(f|e)}
    for(int e : eS){
      float probEF;
      String eTerm = eVocabTrg.get(e);
      int e2 = eVocabSrc.get(eTerm);		//convert between two E vocabs (different ids)
      if(e2 <= 0){
        //				sLogger.warn(eTerm+" word not in aligner's vocab (english side of e2f)");
        continue;
      }
      probEF = e2fProbs.get(e2, f2);

      if(probEF > 0){
        //				sLogger.debug(eVocabSrc.get(e2)+" ==> "+probEF);

        if(tfTable.containsKey(e2)){
          tfTable.put(e2, tfTable.get(e2)+tf*probEF);
        }else{
          tfTable.put(e2, tf*probEF);
        }
      }
    }
    return tfTable;
  }

  /**
   * Given a document in F, and its tf mapping, compute a tf value for each term in E using the CLIR algorithm: tf(e) = sum_f{tf(f)*prob(f|e)}
   * 
   * @param doc
   *	 	mapping from F-term strings to tf values
   * @param tfTable
   * 		to be returned, a mapping from E-term ids to tf values
   * @param eVocabSrc
   * 		source-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param eVocabTrg
   * 		target-side vocabulary of the ttable F-->E (i.e., Pr(f|e))
   * @param fVocabSrc
   * 		source-side vocabulary of the ttable F-->E (i.e., Pr(e|f))
   * @param fVocabTrg
   * 		target-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param e2fProbs
   * 		ttable E-->F (i.e., Pr(f|e))
   * @param f2eProbs
   * 		ttable F-->E (i.e., Pr(e|f))
   * @param sLogger
   * 		Logger object for log output
   * @return
   * @throws IOException
   */
  public static int translateTFs(TermDocVector doc, HMapIFW tfTable, Vocab eVocabSrc, Vocab eVocabTrg, Vocab fVocabSrc, Vocab fVocabTrg, TTable_monolithic_IFAs e2fProbs, TTable_monolithic_IFAs f2eProbs, Tokenizer tokenizer, Logger sLogger) throws IOException{
    if(sLogger == null){
      sLogger = logger;
    }
    //translate doc vector		
    TermDocVector.Reader reader = doc.getReader();
    int docLen = 0;
    while (reader.hasMoreTerms()) {
      String fTerm = reader.nextTerm();
      int tf = reader.getTf();
      docLen+=tf;

//      sLogger.debug("Read "+fTerm+","+tf);

      int f = fVocabSrc.get(fTerm);
      if(f <= 0){
        sLogger.debug("Warning: "+f+","+fTerm+": word not in aligner's vocab (source side of f2e)");
        continue;
      }
      int[] eS = f2eProbs.get(f).getTranslations(0.0f);

      int f2 = fVocabTrg.get(fTerm);		//convert between two F vocabs (different ids)
      if(f2 <= 0){
        sLogger.debug("Warning: "+fTerm+": word not in aligner's vocab (target side of e2f)");
        continue;
      }
      //tf(e) = sum_f{tf(f)*prob(f|e)}
      for(int e : eS){
        if(e<=0){		//if eTerm is NULL, that means there were cases where fTerm was unaligned in a sentence pair. Just skip these cases, since the word NULL is not in our target vocab.
          continue;
        }
        float probEF;
        String eTerm = eVocabTrg.get(e);
        if(tokenizer.isStopWord(eTerm)){
          sLogger.warn("Discarded: "+eTerm+" is a stopword!");
          continue;
        }
        int e2 = eVocabSrc.get(eTerm);		//convert between two E vocabs (different ids)
        if(e2 <= 0){
          sLogger.warn("Warning: "+eTerm+": word not in aligner's final vocab (source side of e2f)");
          continue;
        }
        probEF = e2fProbs.get(e2, f2);
        if(probEF > 0){
//          sLogger.debug(eTerm+" ==> "+probEF);
          tfTable.increment(e2, tf*probEF);
//          sLogger.debug("updated weight to "+tfTable.get(e2));
        }
      }
    }

    return docLen;
  }


  /**
   * Given a document in F, and its tf mapping, compute a tf value for each term in E using the CLIR algorithm: tf(e) = sum_f{tf(f)*prob(f|e)}
   * 
   * @param doc
   *	 	mapping from F-term strings to tf values
   * @param tfTable
   * 		to be returned, a mapping from E-term ids to tf values
   * @param eVocabSrc
   * 		source-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param eVocabTrg
   * 		target-side vocabulary of the ttable F-->E (i.e., Pr(f|e))
   * @param fVocabSrc
   * 		source-side vocabulary of the ttable F-->E (i.e., Pr(e|f))
   * @param fVocabTrg
   * 		target-side vocabulary of the ttable E-->F (i.e., Pr(f|e))
   * @param e2fProbs
   * 		ttable E-->F (i.e., Pr(f|e))
   * @param f2eProbs
   * 		ttable F-->E (i.e., Pr(e|f))
   * @param sLogger
   * 		Logger object for log output
   * @return
   * @throws IOException
   */
  public static int translateTFs(HMapSIW doc, HMapIFW tfTable, Vocab eVocabSrc, Vocab eVocabTrg, Vocab fVocabSrc, Vocab fVocabTrg, TTable_monolithic_IFAs e2fProbs, TTable_monolithic_IFAs f2eProbs, Logger sLogger) throws IOException{
    if(sLogger == null){
      sLogger = logger;
    }

    int docLen = 0;
    for(edu.umd.cloud9.util.map.MapKI.Entry<String> item : doc.entrySet()){
      String fTerm = item.getKey();
      int tf = item.getValue();
      docLen += tf;
      int f = fVocabSrc.get(fTerm);
      if(f <= 0){
        //				sLogger.warn(f+","+fTerm+": word not in aligner's vocab (source side of f2e)");
        continue;
      }
      int[] eS = f2eProbs.get(f).getTranslations(0.0f);

      int f2 = fVocabTrg.get(fTerm);		//convert between two F vocabs (different ids)
      if(f2 <= 0){
        //				sLogger.warn(fTerm+": word not in aligner's vocab (target side of e2f)");
        continue;
      }
      //tf(e) = sum_f{tf(f)*prob(f|e)}
      float prob;
      for(int e : eS){
        String eTerm = eVocabTrg.get(e);
        int e2 = eVocabSrc.get(eTerm);		//convert between two E vocabs (different ids)
        if(e2 <= 0){
          //					sLogger.debug(eTerm+": word not in aligner's final vocab (source side of e2f)");
          continue;
        }
        prob = e2fProbs.get(e2, f2);
        if(prob > 0){
          //					sLogger.debug(eVocabSrc.get(e2)+" ==> "+prob);
          tfTable.increment(e2, tf*prob);
        }
      }
    }

    return docLen;
  }

  /**
   * Given the TF, DF values, doc length, scoring model, this method creates the term doc vector for a document.
   * 
   * @param docLen
   * 		doc length
   * @param tfTable
   * 		mapping from term id to tf values
   * @param eVocabSrc
   * 		vocabulary object for final doc vector language
   * @param scoring model
   * @param dfTable
   * 		mapping from term id to df values
   * @param isNormalize
   * 		indicating whether to normalize the doc vector weights or not
   * @param sLogger
   * 		Logger object for log output
   * @return
   * 		Term doc vector representing the document
   */
  public static HMapSFW createTermDocVector(int docLen, HMapIFW tfTable, Vocab eVocabSrc, ScoringModel scoringModel, HMapIFW dfTable, boolean isNormalize, Logger sLogger) {
    if(sLogger == null){
      sLogger = logger;
    }
    HMapSFW v = new HMapSFW();
    float normalization=0;
    for(int e : tfTable.keySet()){
      // retrieve term string, tf and df
      String eTerm = eVocabSrc.get(e);
      float tf = tfTable.get(e);
      float df = dfTable.get(e);

      // compute score via scoring model
      float score = ((Bm25) scoringModel).computeDocumentWeight(tf, df, docLen);

//      sLogger.debug(eTerm+" "+tf+" "+df+" "+score);
      if(score>0){
        v.put(eTerm, score);
        if(isNormalize){
          normalization+=Math.pow(score, 2);
        }		
      }
    }

    // length-normalize doc vector
    if(isNormalize){
      normalization = (float) Math.sqrt(normalization);
      for(Entry<String> e : v.entrySet()){
        v.put(e.getKey(), e.getValue()/normalization);
      }
    }
    return v;
  }

  /**
   * Given the TF, DF values, doc length, scoring model, this method creates the term doc vector for a document.
   * 
   * @param docLen
   * 		doc length
   * @param tfTable
   * 		mapping from term id to tf values
   * @param eVocabSrc
   * 		vocabulary object for final doc vector language
   * @param scoring model
   * @param dfTable
   * 		mapping from term id to df values
   * @param isNormalize
   * 		indicating whether to normalize the doc vector weights or not
   * @param sLogger
   * 		Logger object for log output
   * @return
   * 		Term doc vector representing the document
   */
  public static HMapSFW createTermDocVector(int docLen, HMapSIW tfTable, Vocab eVocabSrc, ScoringModel scoringModel, FrequencySortedDictionary dict, DfTableArray dfTable, boolean isNormalize, Logger sLogger) {
    if(sLogger == null){
      sLogger = logger;
    }

    HMapSFW v = new HMapSFW();
    float normalization=0;
    for(edu.umd.cloud9.util.map.MapKI.Entry<String> entry : tfTable.entrySet()){
      // retrieve term string, tf and df
      String eTerm = entry.getKey();
      int tf = entry.getValue();
      int eId = dict.getId(eTerm);
      if(eId < 1){		//OOV
        continue;
      }
      int df = dfTable.getDf(eId);
      // compute score via scoring model
      float score = ((Bm25) scoringModel).computeDocumentWeight(tf, df, docLen);
      if(df<1){
        sLogger.warn("Suspicious DF WARNING = "+eTerm+" "+tf+" "+df+" "+score);
      }
      if(score>0){
        v.put(eTerm, score);
        if(isNormalize){
          normalization+=Math.pow(score, 2);
        }		
      }
    }

    // length-normalize doc vector
    if(isNormalize){
      normalization = (float) Math.sqrt(normalization);
      for(Entry<String> e : v.entrySet()){
        v.put(e.getKey(), e.getValue()/normalization);
      }
    }
    return v;
  }

  /**
   * Uses old globalStats code, which is not supported anymore. Only here for backward compatibility
   */
  @Deprecated
  public static HMapSFW createTermDocVector(int docLen, HMapSIW tfTable, Vocab eVocabSrc, ScoringModel scoringModel, PrefixEncodedGlobalStats globalStats, boolean isNormalize, Logger sLogger) {
    if(sLogger == null){
      sLogger = logger;
    }

    HMapSFW v = new HMapSFW();
    float normalization=0;
    for(edu.umd.cloud9.util.map.MapKI.Entry<String> entry : tfTable.entrySet()){
      // retrieve term string, tf and df
      String eTerm = entry.getKey();
      int tf = entry.getValue();

      int df = globalStats.getDF(eTerm);
      if(df<1){		//OOV
        continue;
      }

      // compute score via scoring model
      float score = ((Bm25) scoringModel).computeDocumentWeight(tf, df, docLen);

      if(score>0){
        v.put(eTerm, score);
        if(isNormalize){
          normalization+=Math.pow(score, 2);
        }		
      }
    }

    // length-normalize doc vector
    if(isNormalize){
      normalization = (float) Math.sqrt(normalization);
      for(Entry<String> e : v.entrySet()){
        v.put(e.getKey(), e.getValue()/normalization);
      }
    }
    return v;
  }

  
  /***
   * 
   * Hooka helper functions
   * 
   */
  
  
  /**
   * This method converts the output of BerkeleyAligner into a TTable_monolithic_IFAs object. 
   * For each source language term, top numTrans entries (with highest translation probability) are kept, unless the top K < numTrans entries have a cumulatite probability above PROB_THRESHOLD.
   * 
   * @param inputFile
   * 		output of Berkeley Aligner (probability values from source language to target language). Format should be: 
   * 			[source-word] entropy ... nTrans ... sum 1.000000
   * 				[target-word1]: [prob1]
   * 				[target-word2]: [prob2]
   * 				..
   * @param srcVocabFile
   * 		path where created source vocabulary (VocabularyWritable) will be written
   * @param trgVocabFile
   * 		path where created target vocabulary (VocabularyWritable) will be written
   * @param probsFile
   * 		path where created probability table (TTable_monolithic_IFAs) will be written
   * @param fs
   * 		FileSystem object
   * @throws IOException
   */
  public static void createTTableFromBerkeleyAligner(String inputFile, String srcVocabFile, String trgVocabFile, String probsFile, float probThreshold, int numTrans, FileSystem fs) throws IOException{
    logger.setLevel(Level.INFO);

    TTable_monolithic_IFAs table = new TTable_monolithic_IFAs();
    VocabularyWritable trgVocab = new VocabularyWritable(), srcVocab = new VocabularyWritable();
    File file = new File(inputFile);
    FileInputStream fis = null;
    BufferedReader bis = null;
    int cntLongTail = 0, cntShortTail = 0, sumShortTail = 0, cnt = 0;		// for statistical purposes only
    float sumCumProbs = 0f;											// for statistical purposes only

    //In BerkeleyAligner output, dictionary entries of each source term are already sorted by prob. value. 
    try {
      fis = new FileInputStream(file);

      bis = new BufferedReader(new InputStreamReader(fis,"UTF-8"));
      String cur = null;
      boolean earlyTerminate = false;
      String line = "";
      while (true) {
        if(!earlyTerminate){
          line = bis.readLine();
          if(line ==null)
            break;
          cnt++;
        }
        earlyTerminate = false;
        logger.debug("Line:"+line);

        Pattern p = Pattern.compile("(.+)\\tentropy .+nTrans"); 
        Matcher m = p.matcher(line);
        if(m.find()){
          cur = m.group(1);

          int gerIndex = srcVocab.addOrGet(cur);	
          logger.debug("Found: "+cur+" with index: "+gerIndex);


          List<PairOfIntFloat> indexProbPairs = new ArrayList<PairOfIntFloat>();
          float sumOfProbs = 0.0f;
          for(int i=0;i<numTrans;i++){
            if((line=bis.readLine())!=null){
              cnt++;
              Pattern p2 = Pattern.compile("\\s*(\\S+): (.+)");
              Matcher m2 = p2.matcher(line);
              if(!m2.find()){
                m = p.matcher(line);
                if(m.find()){
                  logger.debug("Early terminate");
                  earlyTerminate = true;
                  i = numTrans;
                  break;
                }
                //								logger.debug("FFFF"+line);
              }else{
                String term = m2.group(1);
                float prob = Float.parseFloat(m2.group(2));
                int engIndex = trgVocab.addOrGet(term);
                logger.debug("Added: "+term+" with index: "+engIndex+" and prob:"+prob);
                indexProbPairs.add(new PairOfIntFloat(engIndex, prob));
                sumOfProbs+=prob;
              }
            }
            if(sumOfProbs > probThreshold){
              cntShortTail++;		// for statistical purposes only
              sumShortTail += (i+1);	// for statistical purposes only
              break;
            }
          }
          if(sumOfProbs <= probThreshold){
            // early termination
            cntLongTail++;		// for statistical purposes only
            sumCumProbs += sumOfProbs;
          }

          // to enable faster access with binary search, we sort entries by vocabulary index.
          Collections.sort(indexProbPairs);
          int i=0;
          int numEntries = indexProbPairs.size();
          int[] indices = new int[numEntries];
          float[] probs = new float[numEntries];
          for(PairOfIntFloat pair : indexProbPairs){
            indices[i] = pair.getLeftElement();
            probs[i++] = pair.getRightElement()/sumOfProbs;
          }
          table.set(gerIndex, new IndexedFloatArray(indices, probs, true));
        }
      }

      // dispose all the resources after using them.
      fis.close();
      bis.close();
      //			dis.close();
    }catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
    logger.info("File "+inputFile+": read "+cnt+" lines");
    logger.info("Vocabulary Target: "+trgVocab.size()+" elements");
    logger.info("Vocabulary Source: "+srcVocab.size()+" elements");
    logger.info("# source terms with > "+probThreshold+" probability covered: "+cntShortTail+" and average translations per term: "+(sumShortTail/(cntShortTail+0.0f)));
    logger.info("# source terms with <= "+probThreshold+" probability covered: "+cntLongTail+" (each have "+ numTrans +" translations). Average coverage is: "+(sumCumProbs/cntLongTail));
    logger.info("Size (total number of dictionary entries) = "+(sumShortTail + cntLongTail*numTrans));


    DataOutputStream dos = new DataOutputStream(new BufferedOutputStream
        (fs.create(new Path(trgVocabFile))));
    ((VocabularyWritable) trgVocab).write(dos);
    dos.close();
    DataOutputStream dos2 = new DataOutputStream(new BufferedOutputStream
        (fs.create(new Path(srcVocabFile))));
    ((VocabularyWritable) srcVocab).write(dos2);
    dos2.close();
    DataOutputStream dos3 = new DataOutputStream(new BufferedOutputStream
        (fs.create(new Path(probsFile))));
    table.write(dos3);
    dos3.close();
  }


  /**
   * This method converts the output of GIZA into a TTable_monolithic_IFAs object. 
   * For each source language term, top numTrans entries (with highest translation probability) are kept, unless the top K < numTrans entries have a cumulatite probability above probThreshold.
   * 
   * @param inputFile
   * 		output of GIZA (probability values from source language to target language. In GIZA, format of each line should be: 
   * 			[target-word1] [source-word] [prob1]
   * 			[target-word2] [source-word] [prob2]
   *          ...
   * @param srcVocabFile
   * 		path where created source vocabulary (VocabularyWritable) will be written
   * @param trgVocabFile
   * 		path where created target vocabulary (VocabularyWritable) will be written
   * @param probsFile
   * 		path where created probability table (TTable_monolithic_IFAs) will be written
   * @param fs
   * 		FileSystem object
   * @throws IOException
   */
  public static void createTTableFromGIZA(String filename, String srcVocabFile, String trgVocabFile, String probsFile, float probThreshold, int numTrans, FileSystem fs) throws IOException{
    logger.setLevel(Level.INFO);

    TTable_monolithic_IFAs table = new TTable_monolithic_IFAs();
    VocabularyWritable trgVocab = new VocabularyWritable(), srcVocab = new VocabularyWritable();
    File file = new File(filename);
    FileInputStream fis = null;
    BufferedReader bis = null;
    int cnt = 0;

    //In GIZA output, dictionary entries are in random order (w.r.t. prob value), so you need to keep a sorted list of top numTrans or less entries w/o exceeding <probThreshold> probability
    try {
      fis = new FileInputStream(file);
      bis = new BufferedReader(new InputStreamReader(fis,"UTF-8"));

      String srcTerm = null, trgTerm = null, prev = null;
      int curIndex = -1;
      TreeSet<PairOfFloatString> topTrans = new TreeSet<PairOfFloatString>();
      String line = "";
      boolean earlyTerminate = false, skipTerm = false;
      float sumOfProbs = 0.0f, prob, sumCumProbs = 0;
      int cntLongTail = 0, cntShortTail = 0, sumShortTail = 0;		// for statistical purposes only

      while (true) {	
        line = bis.readLine();
        if(line == null)	break;
        String[] parts = line.split(" ");
        if(parts.length != 3){
          throw new RuntimeException("Unknown format: "+cnt+" = \n"+line);
        }
        cnt++;
        trgTerm = parts[0];
        srcTerm = parts[1];
        prob = Float.parseFloat(parts[2]);
        if(prev==null || !srcTerm.equals(prev)){
          if(topTrans.size() > 0){
            //store previous term's top translations to ttable
            int finalNumTrans = addToTable(curIndex, topTrans, sumOfProbs, table, trgVocab);
            if(finalNumTrans < numTrans){
              cntShortTail++;
              sumShortTail += finalNumTrans;
            }else{
              cntLongTail++;
              sumCumProbs += sumOfProbs;
            }
          }
          logger.debug("Line:"+line);

          //initialize this term
          sumOfProbs = 0.0f;
          topTrans.clear();
          earlyTerminate = false;		//reset status
          skipTerm = false;
          prev = srcTerm;
          int prevIndex = curIndex;
          curIndex = srcVocab.addOrGet(srcTerm);
          if(curIndex <= prevIndex){
            //we've seen this foreign term before. probably due to tokenization or sorting error in aligner. just ignore.
            curIndex = prevIndex;		//revert curIndex value since we're skipping this one
            skipTerm = true;
            continue;
          }
          logger.debug("Processing: "+srcTerm+" with index: "+curIndex);			
          topTrans.add(new PairOfFloatString(prob, trgTerm));
          sumOfProbs += prob;
        }else if(!earlyTerminate && !skipTerm){	//continue adding translation term,prob pairs (except if early termination is ON)
          topTrans.add(new PairOfFloatString(prob, trgTerm));

          // keep top numTrans translations
          if(topTrans.size() > numTrans){
            float removedProb = topTrans.pollFirst().getLeftElement();
            sumOfProbs -= removedProb;
          }
          sumOfProbs += prob;
        }else{
          logger.debug("Skipped");
        }
        if(sumOfProbs > probThreshold){
          earlyTerminate = true;
          logger.debug("Sum of probs > "+probThreshold+", early termination.");
        }
      }

      //last one
      if(topTrans.size()>0){
        //store previous term's top translations to ttable
        int finalNumTrans = addToTable(curIndex, topTrans, sumOfProbs, table, trgVocab);
        if(finalNumTrans < numTrans){
          cntShortTail++;
          sumShortTail += finalNumTrans;
        }else{
          // early termination: <numTrans> elements did not cover <probThreshold> probability
          cntLongTail++;
          sumCumProbs += sumOfProbs;
        }
      }

      // dispose all the resources after using them.
      fis.close();
      bis.close();
      logger.info("File "+filename+": read "+cnt+" lines");
      logger.info("Vocabulary Target: "+trgVocab.size()+" elements");
      logger.info("Vocabulary Source: "+srcVocab.size()+" elements");
      logger.info("# source terms with > "+probThreshold+" probability covered: "+cntShortTail+" and average translations per term: "+(sumShortTail/(cntShortTail+0.0f)));
      logger.info("# source terms with <= "+probThreshold+" probability covered: "+cntLongTail+" (each have "+ numTrans +" translations). Average coverage is: "+(sumCumProbs/cntLongTail));
      logger.info("Size (total number of dictionary entries) = "+(sumShortTail + cntLongTail*numTrans));
    }catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

    DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fs.create(new Path(trgVocabFile))));
    ((VocabularyWritable) trgVocab).write(dos);
    dos.close();
    DataOutputStream dos2 = new DataOutputStream(new BufferedOutputStream(fs.create(new Path(srcVocabFile))));
    ((VocabularyWritable) srcVocab).write(dos2);
    dos2.close();
    DataOutputStream dos3 = new DataOutputStream(new BufferedOutputStream(fs.create(new Path(probsFile))));
    table.write(dos3);
    dos3.close();
  }

  /**
   * This method modifies the TTable_monolithic_IFAs object output by Hooka, to meet following criteria: 
   * For each source language term, top numTrans entries (with highest translation probability) are kept, unless the top K < numTrans entries have a cumulatite probability above probThreshold.
   * 
   * @param srcVocabFile
   * 		path to source vocabulary file output by Hooka
   * @param trgVocabFile
   * 	 	path to target vocabulary file output by Hooka
   * @param tableFile
   * 		path to ttable file output by Hooka
   * @param finalSrcVocabFile
   * 		path where created source vocabulary (VocabularyWritable) will be written
   * @param finalTrgVocabFile
   * 		path where created target vocabulary (VocabularyWritable) will be written
   * @param finalTableFile
   * 		path where created probability table (TTable_monolithic_IFAs) will be written
   * @param fs
   * 		FileSystem object
   * @throws IOException
   */
  public static void createTTableFromHooka(String srcVocabFile, String trgVocabFile, String tableFile, String finalSrcVocabFile, String finalTrgVocabFile, String finalTableFile, float probThreshold, int numTrans, FileSystem fs) throws IOException{
    logger.setLevel(Level.INFO);

    Vocab srcVocab = HadoopAlign.loadVocab(new Path(srcVocabFile), fs);
    Vocab trgVocab = HadoopAlign.loadVocab(new Path(trgVocabFile), fs);
    TTable_monolithic_IFAs ttable = new TTable_monolithic_IFAs(fs, new Path(tableFile), true);

    Vocab finalSrcVocab = new VocabularyWritable();
    Vocab finalTrgVocab = new VocabularyWritable();
    TTable_monolithic_IFAs finalTTable = new TTable_monolithic_IFAs();

    String srcTerm = null, trgTerm = null;
    int curIndex = -1;
    TreeSet<PairOfFloatString> topTrans = new TreeSet<PairOfFloatString>();
    float sumOfProbs = 0.0f, prob, sumCumProbs = 0f;
    int cntLongTail = 0, cntShortTail = 0, sumShortTail = 0;		// for statistical purposes only

    //modify current ttable wrt foll. criteria: top numTrans translations per source term, unless cumulative prob. distr. exceeds probThreshold before that.
    for(int srcIndex=1; srcIndex<srcVocab.size(); srcIndex++){
      int[] translations;
      try {
        translations = ttable.get(srcIndex).getTranslations(0.0f);
      } catch (Exception e) {
        logger.warn("No translations found for "+srcVocab.get(srcIndex)+". Ignoring...");
        continue;
      }

      srcTerm = srcVocab.get(srcIndex);
      curIndex = finalSrcVocab.addOrGet(srcTerm);

      //initialize this term
      topTrans.clear();
      sumOfProbs = 0.0f;
      logger.debug("Processing: "+srcTerm+" with index: "+curIndex+" ("+srcIndex+")");
      for(int trgIndex : translations){
        trgTerm = trgVocab.get(trgIndex);
        prob = ttable.get(srcIndex, trgIndex);

        topTrans.add(new PairOfFloatString(prob, trgTerm));
        // keep top numTrans translations
        if(topTrans.size() > numTrans){
          float removedProb = topTrans.pollFirst().getLeftElement();
          sumOfProbs -= removedProb;
        }
        sumOfProbs += prob;

        if(sumOfProbs > probThreshold){
          logger.debug("Sum of probs > "+probThreshold+", early termination.");
          break;
        }	
      }

      //store previous term's top translations to ttable
      if(topTrans.size() > 0){
        int finalNumTrans = addToTable(curIndex, topTrans, sumOfProbs, finalTTable, finalTrgVocab);
      
        if(finalNumTrans < numTrans){
          // <numTrans> elements covered more than <probThreshold> probability, so we terminated early
          cntShortTail++;
          sumShortTail += finalNumTrans;
        }else{
          // <numTrans> elements did not cover <probThreshold> probability, do not add any more as an efficiency heuristic
          cntLongTail++;
          sumCumProbs += sumOfProbs;
        }
      }
    }
    logger.info("Vocabulary Target: "+finalTrgVocab.size()+" elements");
    logger.info("Vocabulary Source: "+finalSrcVocab.size()+" elements");
    logger.info("# source terms with > "+probThreshold+" probability covered: "+cntShortTail+" and average translations per term: "+(sumShortTail/(cntShortTail+0.0f)));
    logger.info("# source terms with <= "+probThreshold+" probability covered: "+cntLongTail+" (each have "+ numTrans +" translations). Average coverage is: "+(sumCumProbs/cntLongTail));
    logger.info("Size (total number of dictionary entries) = "+(sumShortTail + cntLongTail*numTrans));

    DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fs.create(new Path(finalTrgVocabFile))));
    ((VocabularyWritable) finalTrgVocab).write(dos);
    dos.close();
    DataOutputStream dos2 = new DataOutputStream(new BufferedOutputStream(fs.create(new Path(finalSrcVocabFile))));
    ((VocabularyWritable) finalSrcVocab).write(dos2);
    dos2.close();
    DataOutputStream dos3 = new DataOutputStream(new BufferedOutputStream(fs.create(new Path(finalTableFile))));
    finalTTable.write(dos3);
    dos3.close();
  }
  
 
  public static int addToTable(int curIndex, TreeSet<PairOfFloatString> topTrans, float cumProb, TTable_monolithic_IFAs table, Vocab trgVocab) {
    List<Integer> sortedIndices = new ArrayList<Integer>();
    HMapIF index2ProbMap = new HMapIF();

    float sumOfProbs = 0.0f;    //only extract the top K<15 if the mass prob. exceeds MAX_probThreshold
    while(!topTrans.isEmpty()){
      PairOfFloatString e = topTrans.pollLast();
      String term = e.getRightElement();
      float pr = e.getLeftElement()/cumProb;
      int trgIndex = trgVocab.addOrGet(term);
      sumOfProbs += pr;

      sortedIndices.add(trgIndex);
      index2ProbMap.put(trgIndex, pr);
    }

    // to enable faster access with binary search, we sort entries by vocabulary index.
    Collections.sort(sortedIndices);
    int numEntries = sortedIndices.size();
    int[] indices = new int[numEntries];
    float[] probs = new float[numEntries];
    int i=0;
    for(int sortedIndex : sortedIndices){
      indices[i]=sortedIndex;
      probs[i]=index2ProbMap.get(sortedIndex);
      logger.debug("Added: "+indices[i]+" with prob: "+probs[i]);
      i++;
    }
    table.set(curIndex, new IndexedFloatArray(indices, probs, true));
    logger.debug(table.get(curIndex));

    return indices.length;
  }
  
  
  /***
   * 
   * Bitext extraction helper functions
   * 
   */
   

  public static String[] computeFeaturesF1(HMapSFW eVector, HMapSFW fVector, float eSentLength, float fSentLength) {
    String[] features = new String[1];

    if(fSentLength == 0 || eSentLength == 0){
      return null;
    }
    float cosine = CLIRUtils.cosineNormalized(eVector, fVector);
    features[0] = "cosine="+cosine;
    return features;
  }

  public static String[] computeFeaturesF2(HMapSFW eVector, HMapSFW fVector, float eSentLength, float fSentLength) {
    String[] features = new String[3];

    if(fSentLength == 0 || eSentLength == 0){
      return null;
    }

    float cosine = CLIRUtils.cosineNormalized(eVector, fVector);
    features[0] = "cosine="+cosine;
    float lengthratio1, lengthratio2;
    lengthratio1 = eSentLength/fSentLength;
    lengthratio2 = fSentLength/eSentLength;
    features[1] = "lengthratio1="+lengthratio1;
    features[2] = "lengthratio2="+lengthratio2;		
    return features;
  }

  public static String[] computeFeaturesF3(HMapSFW eVector, HMapSIW fSrcTfs, HMapSFW fVector, float eSentLength, float fSentLength,
      Vocab eVocabSrc, Vocab eVocabTrg, Vocab fVocabSrc, Vocab fVocabTrg, TTable_monolithic_IFAs e2f_Probs, TTable_monolithic_IFAs f2e_Probs) {
    String[] features = new String[5];

    if(fSentLength == 0 || eSentLength == 0){
      return null;
    }

    float cosine = CLIRUtils.cosineNormalized(eVector, fVector);
    features[0] = "cosine="+cosine;
    float lengthratio1, lengthratio2;
    lengthratio1 = eSentLength/fSentLength;
    lengthratio2 = fSentLength/eSentLength;
    features[1] = "lengthratio1="+lengthratio1;
    features[2] = "lengthratio2="+lengthratio2;				
    int cntTrans = 0, cntTrans2 = 0;
    float cnt = 0, transratio = 0.0f, cnt2 = 0, transratio2 = 0.0f;
    for(String fTerm : fSrcTfs.keySet()){
      int f = fVocabSrc.get(fTerm);
      if(f < 0){
        continue;
      }
      int[] eS = f2e_Probs.get(f).getTranslations(0.0f);
      for(int e : eS){
        String eTerm = eVocabTrg.get(e);
        if(eVector.containsKey(eTerm)){
          cntTrans++;
          break;
        }
      }
      cnt++;
    }
    for(String eTerm : eVector.keySet()){
      int e = eVocabSrc.get(eTerm);
      if(e < 0){
        continue;
      }
      int[] fS = e2f_Probs.get(e).getTranslations(0.0f);
      for(int f : fS){
        String fTerm = fVocabTrg.get(f);
        if(fVector.containsKey(fTerm)){
          cntTrans2++;
          break;
        }
      }
      cnt2++;
    }
    //when there are terms in fSent but none of them has a translation or vocab entry, set trans ratio to 0
    if(cnt!=0){
      transratio = cntTrans/cnt;
    }			
    if(cnt2!=0){
      transratio2 = cntTrans2/cnt2;
    }
    features[3] ="wordtransratio1="+transratio;
    features[4] ="wordtransratio2="+transratio2;
    return features;
  }


  private static int printUsage() {
    System.out.println("usage: [input-lexicalprob-file_f2e] [input-lexicalprob-file_e2f] [type=giza|berkeley] [src-vocab_f] [trg-vocab_e] [prob-table_f-->e] [src-vocab_e] [trg-vocab_f] [prob-table_e-->f] ([cumulative-prob-threshold]) ([num-max-entries-per-word])");

    return -1;
  }


  public static void main(String args[]){
    if(args.length < 9){
      printUsage();
    }

    // Read parameters
    float probThreshold = 0.9f;
    int numTrans = 15;
    if(args.length >= 10){
      try {
        probThreshold = Float.parseFloat(args[9]);
      } catch (NumberFormatException e) {
        e.printStackTrace();
      }
    }
    if(args.length >= 11){
      try {
        numTrans = Integer.parseInt(args[10]);
      } catch (NumberFormatException e) {
        e.printStackTrace();
      }
    }

    String lex_f2e = args[0];
    String lex_e2f = args[1];
    String type = args[2];
    Configuration conf = new Configuration();
    logger.info("Type of input:" + type);
    try {
      FileSystem localFS = FileSystem.getLocal(conf);
      if(type.equals("giza")){
        CLIRUtils.createTTableFromGIZA(lex_f2e, args[3], args[4], args[5], probThreshold, numTrans, localFS);
        CLIRUtils.createTTableFromGIZA(lex_e2f, args[6], args[7], args[8], probThreshold, numTrans, localFS);
      }else if(type.equals("berkeley")){
        CLIRUtils.createTTableFromBerkeleyAligner(lex_f2e, args[3], args[4], args[5], probThreshold, numTrans, localFS);
        CLIRUtils.createTTableFromBerkeleyAligner(lex_e2f, args[6], args[7], args[8], probThreshold, numTrans, localFS);
      }else{
        printUsage();
      }

      //debugging

      //			TTable_monolithic_IFAs de2EnProbs = new TTable_monolithic_IFAs(localFS, new Path(args[5]), true);
      //			TTable_monolithic_IFAs en2DeProbs = new TTable_monolithic_IFAs(localFS, new Path(args[8]), true);
      //			
      //			Vocab enSrc = HadoopAlign.loadVocab(new Path(args[6]), conf);
      //			Vocab zhTrg = HadoopAlign.loadVocab(new Path(args[7]), conf);
      //
      //			int[] fS = en2DeProbs.get(enSrc.get("princess")).getTranslations(0.0f);
      //			System.out.println(fS.length);
      //
      //			for(int f : fS){
      //				String fTerm =  zhTrg.get(f);
      //				System.out.println(fTerm+","+de2EnProbs.get(enSrc.get("princess"), f));		
      //			}

    } catch (IOException e) {
      e.printStackTrace();
    }



  }

}
