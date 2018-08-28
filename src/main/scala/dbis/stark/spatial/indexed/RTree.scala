package dbis.stark.spatial.indexed

import dbis.stark.STObject.GeoType
import dbis.stark.{Distance, STObject}
import org.locationtech.jts.geom.{Coordinate, Envelope}
import org.locationtech.jts.index.strtree.{AbstractNode, ItemBoundable, ItemDistance, STRtreePlus}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/*var ts: Int, */
protected[indexed] class Data[D](val data: D, val so: STObject) extends Serializable

/**
 * A R-Tree abstraction based on VividSolution's ST R-Tree implementation
 * 
 * @param capacity The number of elements in a node
 */
class RTree[D: ClassTag ](
    @transient private val capacity: Int = 10
  ) extends STRtreePlus[Data[D]](capacity) with Index[D] with KnnIndex[D] with WithinDistanceIndex[D] { // we extend the STRtreePlus (based on JTSPlus) which implements kNN search

  /**
   * Insert data into the tree
   * 
   * @param geom The geometry (key) to index
   * @param data The associated value
   */
  def insert(geom: STObject, data: D): Unit =
    super.insert(geom.getGeo.getEnvelopeInternal, new Data(data, geom))

  def insert(mbr: GeoType, data: D) =
    super.insert(mbr.getEnvelopeInternal, new Data(data, mbr))
  
  /**
   * Query the tree and find all elements in the tree that intersect
   * with the query geometry
   * 
   * @param box The geometry to compute intersection for
   * @return Returns all elements of the tree that intersect with the query geometry
   */
  def oldQuery(box: STObject): Iterator[D] =
    super.query(box.getGeo.getEnvelopeInternal).iterator().asScala.map(_.asInstanceOf[Data[D]].data)

  def query(box: STObject): Iterator[D] =
    super.iteratorQuery(box.getGeo.getEnvelopeInternal).asScala.map(_.data)

  /**
   * A read only query variant of the tree.
   * 
   * A query only increases the timestamp of an item.
   */
//  def queryRO(qry: STObject, pred: (STObject, STObject) => Boolean) = doQueryRO(qry, qry.getEnvelopeInternal, pred)
  
//  def withinDistanceRO(qry: STObject, distFunc: (STObject,STObject) => Double, maxDist: Double) = {
//    val env = qry.getGeo.getEnvelopeInternal
//    val env2 = new Envelope(
//        new Coordinate(env.getMinX - maxDist - 1, env.getMinY - maxDist - 1),
//        new Coordinate(env.getMaxX + maxDist + 1, env.getMaxY + maxDist + 1))
//
//    def pred(g1: STObject, g2: STObject) = distFunc(g1,g2) <= maxDist
//
//    doQueryRO(qry, env2, pred)
//  }
  
  
  override def withinDistance(qry: STObject, distFunc: (STObject,STObject) => Distance, maxDist: Distance) = {
    val env = qry.getGeo.getEnvelopeInternal
    val env2 = new Envelope(
        new Coordinate(env.getMinX - maxDist.maxValue - 1, env.getMinY - maxDist.maxValue - 1),
        new Coordinate(env.getMaxX + maxDist.maxValue + 1, env.getMaxY + maxDist.maxValue + 1))
    
    super.query(env2).iterator().map(_.asInstanceOf[Data[D]]).filter { p => distFunc(qry, p.so) <= maxDist }.map(_.data)
  }
  
//  private def doQueryRO(qry: STObject, env: Envelope, pred: (STObject, STObject) => Boolean) = {
//    class MyVisitor(ts: Int) extends ItemVisitor {
//
//      override def visitItem(item: Any) {
//        val i = item.asInstanceOf[Data[G,D]]
//        if(i.ts == ts - 1 && pred(i.so, qry) )
//          i.ts += 1
//      }
//    }
//
//    super.query(env, new MyVisitor(timestamp))
//    timestamp += 1 // increment timestamp for next query
//  }
  
  /**
   * Helper method to convert and unnest a list of Data elements into 
   * a flat list of Data items
   * 
   * @param l The list that contains plain elements and other lists
   * @return Returns a flat list Data
   */
  private def unnest[T](l: java.lang.Iterable[_]): Iterator[Data[D]] =
    l.flatMap {
      case d: Data[D] => Iterator.single(d)
      case a: java.util.ArrayList[_] => unnest(a)
    }.toIterator
  
  /**
   * Get all items in the tree
   * 
   * @return Returns a list containing all Data items in the tree
   */
  def items = super.itemsTree()
      .iterator()
      .flatMap{ l => (l: @unchecked) match {
        case d: Data[D] => Iterator.single(d)
        case a: java.util.ArrayList[_] => unnest(a)
        } 
      }

  def lastLevelNodes = boundablesAtLevel(depth()).asScala.map(_.asInstanceOf[AbstractNode])

  /**
   * If the tree was queried using the *RO methods you can use this method
   * to retreive the final result of the tree.
   * 
   * @return Returns the list of elements that are the result of previous queries  
   */
//  def result: List[D] = items.filter { d => d.ts == timestamp - 1 }.map(_.data).toList
  
  /**
   * Query the tree to find k nearest neighbors.
   * 
   * @param geom The reference object
   * @param k The number of neighbors  
   */
  override def kNN(geom: STObject, k: Int, distFunc: (STObject, STObject) => Distance): Iterator[D] = {
    if(size <= 0)
      Iterator.empty
    else
      super.kNearestNeighbour(geom.getEnvelopeInternal, geom, new DataDistance(distFunc), k).iterator().map(_.data)
  }

  override private[indexed] def root() = getRoot
}

/**
 * Companion object containing helper method
 */
protected[stark] object DataDistance {

  def getGeo(o: AnyRef): STObject = o match {
      case so: STObject => so //.getGeo
      case d: Data[_] => d.so //.getGeo
      case _ => throw new IllegalArgumentException(s"unsupported type: ${o.getClass}")
    } 
}

/**
 * A distance metric that is used internally by the STRtree for comparing entries 
 */
protected[stark] class DataDistance[G <: STObject,D](
      distFunc: (STObject, STObject) => Distance
    )extends ItemDistance {


  def distance(a: ItemBoundable, b: ItemBoundable): Double = {
  
    val dataA = DataDistance.getGeo(a.getItem)
    val dataB = DataDistance.getGeo(b.getItem)
    
//    dataA.distance(dataB)
    distFunc(dataA, dataB).minValue
    
  }
}

