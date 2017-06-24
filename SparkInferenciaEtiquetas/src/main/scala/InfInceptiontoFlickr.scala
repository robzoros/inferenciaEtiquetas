import java.util.Calendar

import ExtraerNombreFicheros.toLongNombreImagen
import org.apache.spark.ml.classification.{DecisionTreeClassificationModel, DecisionTreeClassifier}
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{SaveMode, SparkSession}

/**
  * Created by utad on 6/17/17.
  */
object InfInceptiontoFlickr {
  def main(args: Array[String]) {

    val directorio = args.toList match {
      case Nil => "hdfs://master.spark.tfm:9000/user/utad/"
      case arg :: Nil => if (arg.last == '/') arg else arg + "/"
      case _ => println("Uso: spark-submit \\\n  --class InfInceptiontoFlickr \\\n  --master url-master \\\n  url-jar/sparkinferenciaetiquetas_2.11-1.0.jar [directorio]"); System.exit(1)
    }

    println("Directorio resultados: " + directorio)
    println("Inicio Proceso: " + Calendar.getInstance.getTime.toString)
    val spark = SparkSession
      .builder()
      .appName("Modelo Inferencia Etiquetas")
      .getOrCreate()

    import spark.implicits._

    // Leemos etiqueta de Flickr
    println("Leemos y filtramos y transformamos Etiquetas Flickr: " + Calendar.getInstance.getTime.toString)
    val labelFlickrDF = spark.read.json(directorio + "mirflickr/labels-images/")
      .groupBy("image")
      .agg(collect_list("label_normalized") as "classification")

    // Leemos lista de imagenes de Inception y todas las etiquetas de inception que guardamos en un array
    println("Leemos imagenes-etiquetas de Inception: " + Calendar.getInstance.getTime.toString )
    val scoresInceptionDS = spark.read.json(directorio + "inception/classification/").as[ScoresInception]
    val labelsInception = scoresInceptionDS.select("label").distinct.sort("label").rdd.map(_.getString(0))collect()

    val cuentaEtiquetas = labelsInception.length
    println("Cuenta Etiquetas: " + cuentaEtiquetas)

    // Mandamos a los workers el array y la cuenta de etiquetas
    spark.sparkContext.broadcast(labelsInception)
    spark.sparkContext.broadcast(cuentaEtiquetas)

    // Transformamos clasificación de inception en Vector
    println("Leemos imagenes-etiquetas de Inception: " + Calendar.getInstance.getTime.toString )
    val labelInceptionRDD = scoresInceptionDS.groupBy("image")
      .agg(collect_list("label") as "labels", collect_list("score") as "scores")
      .rdd
      .map(fila => (fila.getString(0), fila.getSeq[String](1).sorted, fila.getSeq(2)))

    val labelCaracteristicas  = labelInceptionRDD
      .map { case (image, labels, scores) =>
        (toLongNombreImagen(image), Vectors.sparse(cuentaEtiquetas, labels.map(labelsInception.indexOf).toArray, scores.toArray) )
      }
      .toDF("imagen", "features")

    labelCaracteristicas.show()

    // Hacemos join entre los dos DataFrames
    println("Join entre Inception y MIRFLICKR: " + Calendar.getInstance.getTime.toString )
    // Row(id   |label|image|labels )
    val joinDF = labelFlickrDF
      .join(labelCaracteristicas, labelFlickrDF("image") === labelCaracteristicas("imagen"))

    joinDF.show()

    // Dividimos datos para entrenar y probar.
    println("Dividimos datos para entrenar y probar.: " + Calendar.getInstance.getTime.toString )
    val Array(trainingData, testData) = joinDF.select("image", "classification", "features").randomSplit(Array(0.8, 0.2))


    /**
      *
      * @param etiqueta: Etiqueta a Entrenar
      * @return Un modelo entrenado de decision tree para la etiqueta
      */
    def entrenarModelo(etiqueta: String) : ( String, DecisionTreeClassificationModel ) = {

      println("\n**************************************************************")
      println(" Entrenamos etiqueta: " + etiqueta)
      println("**************************************************************\n")

      // Añadimos columna con el valor para la etiqueta que vamos a entrenar
      // Pasamos de ("image", "classification", "features") a ("id", "label", "features")
      val trainingDataEtiqueta = trainingData
        .map(r => Clasificacion(r.getString(0).toLong, r.getSeq(1).toArray[String].count(_ == etiqueta), r.getAs("features")))

      println(trainingDataEtiqueta.count)
      println(trainingDataEtiqueta.filter("label == 1").count)
      println(trainingDataEtiqueta.filter("label == 0").count)

      println("Entrenamos Modelo: " + Calendar.getInstance.getTime.toString )
      // Entrenamos modelo
      val dtc = new DecisionTreeClassifier()
        .setLabelCol("label")
        .setFeaturesCol("features")
        .setImpurity("gini")
        .setMaxDepth(8)

      val modelDT = dtc.fit(trainingDataEtiqueta)

      (etiqueta, modelDT)
    }

    // Leemos etiquetas más comunes de Inception y las distribuimos con un RDD
    println("Leemos etiquetas más comunes de Inception: " + Calendar.getInstance.getTime.toString)
    val listaModelosEntrenados = spark.sparkContext.textFile(directorio + "mirflickr/comunes/").collect.map(entrenarModelo)

    // Hacemos predicciones
    println("Hacemos predicciones: " + Calendar.getInstance.getTime.toString )
    val predicciones = listaModelosEntrenados
      .map(modelo => modelo._2.transform(testData).filter("prediction == 1").map(fila => EtiquetaImagen(fila.getString(0).toLong, modelo._1) ))
      .reduce(_.union(_))

    predicciones.show(false)
    predicciones.coalesce(6).write.mode(SaveMode.Overwrite).json(directorio + "mirflickr/predicciones/")

  }
}
