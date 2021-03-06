package Test

import java.util.Date

import Util.GetIdAbstractRDDForTF
import org.apache.log4j.{Level, Logger}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.feature.{HashingTF, IDF}
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.{SparseVector => SV}
import breeze.linalg._

object TestDemo {
  def main(args: Array[String]) {
    var start_time =new Date().getTime
    println(start_time)
     Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
     Logger.getLogger("org.apache.eclipse.jetty.server").setLevel(Level.OFF)
    val conf = new SparkConf().setAppName("TextClassificationDemo").setMaster("local[2]")
    val sc = new SparkContext(conf)
      //Load documents (one per line).要求每行作为一个document,这里zipWithIndex将每一行的行号作为doc id
      val documents:RDD[(Seq[String],Long)] = GetIdAbstractRDDForTF.work("word.txt",sc)
      //documents.foreach(println)
      documents.collectAsMap()


    val hashingTF = new HashingTF(Math.pow(2, 18).toInt)
    //这里将每一行的行号作为doc id，每一行的分词结果生成tf词频向量
    val tf_num_pairs = documents.map {
      case (seq, num) =>
        //println(num)
        val tf = hashingTF.transform(seq)
        (num, tf)
    }
    documents.unpersist()
    tf_num_pairs.cache()
    //构建idf model
    val idf = new IDF().fit(tf_num_pairs.values)
    //将tf向量转换成tf-idf向量
    val num_idf_pairs = tf_num_pairs.mapValues(v => idf.transform(v))
    tf_num_pairs.unpersist()
    //广播一份tf-idf向量集
    val b_num_idf_pairs = sc.broadcast(num_idf_pairs.collect())

    //计算doc之间余弦相似度
    val docSims = num_idf_pairs.flatMap {
      case (id1, idf1) =>
        val idfs = b_num_idf_pairs.value.filter(_._1  > id1)
        val sv1 = idf1.asInstanceOf[SV]
        val bsv1 = new SparseVector[Double](sv1.indices, sv1.values, sv1.size)
        idfs.map {
          case (id2, idf2) =>
            val sv2 = idf2.asInstanceOf[SV]
            val bsv2 = new SparseVector[Double](sv2.indices, sv2.values, sv2.size)
            val cosSim = bsv1.dot(bsv2) / (norm(bsv1) * norm(bsv2))
            (id1, id2, cosSim)
        }
    }
    num_idf_pairs.unpersist()
    b_num_idf_pairs.unpersist()
    docSims
      .filter(x => x._3 < 0.9 && x._3 > 0.001 )
        .foreach(println)

    docSims.unpersist()
    var stop_time =new Date().getTime
    println(stop_time)
    println(stop_time-start_time)
    sc.stop()

  }

}
