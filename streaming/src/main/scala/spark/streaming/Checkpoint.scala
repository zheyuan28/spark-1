package spark.streaming

import spark.{Logging, Utils}

import org.apache.hadoop.fs.{FileUtil, Path}
import org.apache.hadoop.conf.Configuration

import java.io.{InputStream, ObjectStreamClass, ObjectInputStream, ObjectOutputStream}


class Checkpoint(@transient ssc: StreamingContext, val checkpointTime: Time)
  extends Logging with Serializable {
  val master = ssc.sc.master
  val framework = ssc.sc.jobName
  val sparkHome = ssc.sc.sparkHome
  val jars = ssc.sc.jars
  val graph = ssc.graph
  val checkpointDir = ssc.checkpointDir
  val checkpointInterval = ssc.checkpointInterval

  validate()

  def validate() {
    assert(master != null, "Checkpoint.master is null")
    assert(framework != null, "Checkpoint.framework is null")
    assert(graph != null, "Checkpoint.graph is null")
    assert(checkpointTime != null, "Checkpoint.checkpointTime is null")
    logInfo("Checkpoint for time " + checkpointTime + " validated")
  }

  def save(path: String) {
    val file = new Path(path, "graph")
    val conf = new Configuration()
    val fs = file.getFileSystem(conf)
    logDebug("Saving checkpoint for time " + checkpointTime + " to file '" + file + "'")
    if (fs.exists(file)) {
      val bkFile = new Path(file.getParent, file.getName + ".bk")
      FileUtil.copy(fs, file, fs, bkFile, true, true, conf)
      logDebug("Moved existing checkpoint file to " + bkFile)
    }
    val fos = fs.create(file)
    val oos = new ObjectOutputStream(fos)
    oos.writeObject(this)
    oos.close()
    fs.close()
    logInfo("Checkpoint of streaming context for time " + checkpointTime + " saved successfully to file '" + file + "'")
  }

  def toBytes(): Array[Byte] = {
    val bytes = Utils.serialize(this)
    bytes
  }
}

object Checkpoint extends Logging {

  def load(path: String): Checkpoint = {

    val fs = new Path(path).getFileSystem(new Configuration())
    val attempts = Seq(new Path(path, "graph"), new Path(path, "graph.bk"), new Path(path), new Path(path + ".bk"))

    attempts.foreach(file => {
      if (fs.exists(file)) {
        logInfo("Attempting to load checkpoint from file '" + file + "'")
        try {
          val fis = fs.open(file)
          // ObjectInputStream uses the last defined user-defined class loader in the stack
          // to find classes, which maybe the wrong class loader. Hence, a inherited version
          // of ObjectInputStream is used to explicitly use the current thread's default class
          // loader to find and load classes. This is a well know Java issue and has popped up
          // in other places (e.g., http://jira.codehaus.org/browse/GROOVY-1627)
          val ois = new ObjectInputStreamWithLoader(fis, Thread.currentThread().getContextClassLoader)
          val cp = ois.readObject.asInstanceOf[Checkpoint]
          ois.close()
          fs.close()
          cp.validate()
          logInfo("Checkpoint successfully loaded from file '" + file + "'")
          logInfo("Checkpoint was generated at time " + cp.checkpointTime)
          return cp
        } catch {
          case e: Exception =>
            logError("Error loading checkpoint from file '" + file + "'", e)
        }
      } else {
        logWarning("Could not load checkpoint from file '" + file + "' as it does not exist")
      }

    })
    throw new Exception("Could not load checkpoint from path '" + path + "'")
  }

  def fromBytes(bytes: Array[Byte]): Checkpoint = {
    val cp = Utils.deserialize[Checkpoint](bytes)
    cp.validate()
    cp
  }
}

class ObjectInputStreamWithLoader(inputStream_ : InputStream, loader: ClassLoader) extends ObjectInputStream(inputStream_) {
  override def resolveClass(desc: ObjectStreamClass): Class[_] = {
    try {
      return loader.loadClass(desc.getName())
    } catch {
      case e: Exception =>
    }
    return super.resolveClass(desc)
  }
}
