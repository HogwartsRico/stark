package dbis.stark

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import dbis.stark.spatial.indexed.RTree
import dbis.stark.spatial.partitioner.CellHistogram
import dbis.stark.spatial._
import org.locationtech.jts.geom._
import org.locationtech.jts.io.{WKTReader, WKTWriter}

import scala.collection.mutable.ListBuffer

class SkylineSerializer extends Serializer[Skyline[Any]] {
  val stobjectSerializer = new STObjectSerializer

  override def write(kryo: Kryo, output: Output, skyline: Skyline[Any]): Unit = {
    output.writeInt(skyline.skylinePoints.length, true)
    skyline.skylinePoints.foreach { case (so,v) =>
      kryo.writeObject(output, so, stobjectSerializer)
      kryo.writeClassAndObject(output, v)
    }
    kryo.writeClassAndObject(output, skyline.dominatesFunc)
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[Skyline[Any]]): Skyline[Any] = {
    val num = input.readInt(true)
    var i = 0
    val l = ListBuffer.empty[(STObject, Any)]
    while(i < num) {
      val so = kryo.readObject(input, classOf[STObject], stobjectSerializer)
      val v = kryo.readClassAndObject(input)
      l += ((so,v))

      i += 1
    }
    val func = kryo.readClassAndObject(input).asInstanceOf[(STObject,STObject) => Boolean]

    new Skyline[Any](l.toList, func)
  }
}

object DistanceSerializer {
  private val SCALARDIST: Byte = 0x0
  private val INTERVALDIST: Byte = 0x1
}

class DistanceSerializer extends Serializer[Distance] {
  override def write(kryo: Kryo, output: Output, dist: Distance): Unit = dist match {
    case sd: ScalarDistance =>
      output.writeByte(DistanceSerializer.SCALARDIST)
      output.writeDouble(sd.value)
    case IntervalDistance(min,max) =>
      output.writeByte(DistanceSerializer.INTERVALDIST)
      output.writeDouble(min)
      output.writeDouble(max)
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[Distance]): Distance = input.readByte() match {
    case DistanceSerializer.SCALARDIST =>
      val v = input.readDouble()
      ScalarDistance(v)
    case DistanceSerializer.INTERVALDIST =>
      val min = input.readDouble()
      val max = input.readDouble()
      IntervalDistance(min, max)
  }
}

class KnnSerializer extends Serializer[KNN[Any]] {
  val distSerializer = new DistanceSerializer
  override def write(kryo: Kryo, output: Output, knn: KNN[Any]): Unit = {

    output.writeInt(knn.k, true)
    output.writeInt(knn.posMax, true)
    output.writeInt(knn.posMin, true)
    output.writeInt(knn.m, true)

    var firstNull = knn.nn.indexWhere(_ == null)
    firstNull = if(firstNull < 0) knn.k else firstNull

    output.writeInt(firstNull, true)

    var i = 0
    while(i < firstNull) {
      val (dist,v) = knn.nn(i)
      kryo.writeObject(output, dist, distSerializer)
      kryo.writeClassAndObject(output, v)

      i += 1
    }

  }

  override def read(kryo: Kryo, input: Input, `type`: Class[KNN[Any]]): KNN[Any] = {
    val k = input.readInt(true)
    val posMax = input.readInt(true)
    val posMin = input.readInt(true)
    val m = input.readInt(true)

    var firstNull = input.readInt(true)
    firstNull = if(firstNull < 0) k else firstNull

    val objs = new Array[(Distance,Any)](k)

    var i = 0
    while(i < firstNull) {
      val dist = kryo.readObject(input, classOf[Distance], distSerializer)
      val v = kryo.readClassAndObject(input).asInstanceOf[Any]

      objs(i) = (dist,v)
      i += 1
    }


    val knn = new KNN[Any](k)
    knn.m = m
    knn.posMin = posMin
    knn.posMax = posMax
    knn.nn = objs

//    println(s"\ndeserialied $knn")

    knn
  }
}

class RTreeSerializer extends Serializer[RTree[Any]] {
  val soSerializer = new STObjectSerializer
  override def write(kryo: Kryo, output: Output, tree: RTree[Any]): Unit = {
    tree.build()

    output.writeInt(tree.getNodeCapacity, true)
    output.writeInt(tree.size(), true)

    tree._items.foreach{ d =>
      kryo.writeObject(output, d.so, soSerializer)
      kryo.writeClassAndObject(output, d.data)
    }
  }

  override def read(kryo: Kryo, input: Input, dType: Class[RTree[Any]]): RTree[Any] = {
    val capacity = input.readInt(true)
    val size = input.readInt(true)

    val tree = new RTree[Any](capacity)

    var i = 0
    while(i < size) {
      val so = kryo.readObject(input, classOf[STObject], soSerializer)
      val data = kryo.readClassAndObject(input).asInstanceOf[Any]

      tree.insert(so, data)
      i += 1
    }

    tree.build()
    tree
  }
}

class NPointSerializer extends Serializer[NPoint] {
  override def write(kryo: Kryo, output: Output, p: NPoint): Unit = {
    output.writeInt(p.dim, true)
    var i = 0
    while(i < p.dim) {
      output.writeDouble(p(i))
      i += 1
    }
  }

  override def read(kryo: Kryo, input: Input, dType: Class[NPoint]): NPoint = {
    val dim = input.readInt(true)
    var i = 0
    val arr = new Array[Double](dim)
    while(i < dim) {
      arr(i) = input.readDouble()
      i += 1
    }

    NPoint(arr)
  }
}

class NRectSerializer extends Serializer[NRectRange] {
  val pointSer = new NPointSerializer()

  override def write(kryo: Kryo, output: Output, rect: NRectRange): Unit = {
    pointSer.write(kryo, output, rect.ll)
    pointSer.write(kryo, output, rect.ur)
  }

  override def read(kryo: Kryo, input: Input, dType: Class[NRectRange]): NRectRange = {
    val ll = pointSer.read(kryo, input, classOf[NPoint])
    val ur = pointSer.read(kryo, input, classOf[NPoint])
    NRectRange(ll, ur)
  }
}

class CellSerializer extends Serializer[Cell] {
  val rectSerializer = new NRectSerializer
  override def write(kryo: Kryo, output: Output, cell: Cell) = {
    output.writeInt(cell.id, true)
    kryo.writeObject(output, cell.range, rectSerializer)
    val same = cell.range == cell.extent
    output.writeBoolean(same)
    if(!same)
      kryo.writeObject(output, cell.extent, rectSerializer)
  }

  override def read(kryo: Kryo, input: Input, dType: Class[Cell]) = {
    val id = input.readInt(true)
    val range = kryo.readObject(input, classOf[NRectRange], rectSerializer)
    val same = input.readBoolean()
    if(!same) {
      val extent = kryo.readObject(input, classOf[NRectRange], rectSerializer)
      Cell(id, range, extent)
    }
    else
      Cell(id, range)
  }
}

class HistogramSerializer extends Serializer[CellHistogram] {
  val cellSerializer = new CellSerializer
  override def write(kryo: Kryo, output: Output, histo: CellHistogram) = {
    output.writeInt(histo.buckets.length, true)
    var i = 0
    while(i < histo.buckets.length) {
      val (cell, cnt) = histo.buckets(i)
      kryo.writeObject(output, cell, cellSerializer)
      output.writeInt(cnt, true)

      i += 1
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[CellHistogram]) = {
    val num = input.readInt(true)
    val buckets = new Array[(Cell, Int)](num)
    var i = 0
    while(i < num) {
      val cell = kryo.readObject(input, classOf[Cell], cellSerializer)
      val cnt = input.readInt(true)

      buckets(i) = (cell, cnt)

      i += 1
    }

    CellHistogram(buckets)
  }
}

class EnvelopeSerializer extends Serializer[Envelope] {
  override def write(kryo: Kryo, output: Output, obj: Envelope): Unit = {
    output.writeDouble(obj.getMinX)
    output.writeDouble(obj.getMinY)
    output.writeDouble(obj.getMaxX)
    output.writeDouble(obj.getMaxY)
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[Envelope]): Envelope = {
    val minX = input.readDouble()
    val minY = input.readDouble()
    val maxX = input.readDouble()
    val maxY = input.readDouble()

    new Envelope(minX, maxX, minY, maxY)
  }
}

object GeometrySerializer {
  val POINT: Byte = 0x0
  val POLYGON: Byte = 0x1
  val LINESTRING: Byte = 0x2
}

class GeometryAsStringSerializer extends Serializer[Geometry] {
  override def write(kryo: Kryo, output: Output, geom: Geometry) = {
    val writer = new WKTWriter(2)
    val offset = geom match {
      case _:Point =>
        output.writeByte(GeometrySerializer.POINT)
        "point".length
      case _:LineString =>
        output.writeByte(GeometrySerializer.LINESTRING)
        "linestring".length
      case _:Polygon =>
        output.writeByte(GeometrySerializer.POLYGON)
        "polygon".length
    }

    val txt = writer.write(geom)
    val toWrite = txt.substring(offset, txt.length - 1)
    output.writeString(toWrite)
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[Geometry]) = {
    val reader = new WKTReader()

    val geoType = input.readByte()
    val str = input.readString()

    val strType = geoType match {
      case GeometrySerializer.POINT => "POINT("
      case GeometrySerializer.LINESTRING => "LINESTRING("
      case GeometrySerializer.POLYGON => "POLYGON("
    }

    reader.read(s"$strType$str)")
  }
}

class GeometrySerializer extends Serializer[Geometry] {

  private val geometryFactory = new GeometryFactory()

  private def writePoint(x: Double, y: Double, output: Output): Unit = {
    output.writeDouble(x)
    output.writeDouble(y)
  }

  private def writeLineString(l: LineString, output: Output) = {
    val nPoints = l.getNumPoints
    output.writeInt(nPoints, true)
    var i = 0
    while(i < nPoints) {
      val p = l.getPointN(i)
      writePoint(p.getX, p.getY, output)
      i += 1
    }
  }

  private def readPoint(input: Input): Point = {
    val c = readPointInternal(input)
    geometryFactory.createPoint(c)
  }

  private def readPointInternal(input: Input): Coordinate = {
    val x = input.readDouble()
    val y = input.readDouble()
    new Coordinate(x,y)
  }


  override def write(kryo: Kryo, output: Output, obj: Geometry): Unit = obj match {
    case p: Point =>
      output.writeByte(GeometrySerializer.POINT)
      writePoint(p.getX, p.getY, output)
    case l: LineString =>
      output.writeByte(GeometrySerializer.LINESTRING)
      writeLineString(l, output)
    case p: Polygon =>
      output.writeByte(GeometrySerializer.POLYGON)
      val extRing = p.getExteriorRing
      writeLineString(extRing, output)
  }

  override def read(kryo: Kryo, input: Input, dType: Class[Geometry]): Geometry = input.readByte() match {
    case GeometrySerializer.POINT =>
      readPoint(input)

    case GeometrySerializer.LINESTRING =>
      val nPoints = input.readInt(true)
      val points = new Array[Coordinate](nPoints)
      var i = 0
      while(i < nPoints) {
        val c = readPointInternal(input)
        points(i) = c
        i += 1
      }

      geometryFactory.createLineString(points)

    case GeometrySerializer.POLYGON =>
      val nPoints = input.readInt(true)
      val points = new Array[Coordinate](nPoints)
      var i = 0
      while(i < nPoints) {
        val c = readPointInternal(input)
        points(i) = c
        i += 1
      }

      geometryFactory.createPolygon(points)
  }
}

object TemporalSerializer {
  private val INSTANT: Byte = 0x0
  private val INTERVAL: Byte = 0x1
}

class TemporalSerializer extends Serializer[TemporalExpression] {
  override def write(kryo: Kryo, output: Output, obj: TemporalExpression): Unit = obj match {
    case Instant(t) =>
      output.writeByte(TemporalSerializer.INSTANT)
      output.writeLong(t, true)
    case Interval(start, end) =>
      output.writeByte(TemporalSerializer.INTERVAL)
      kryo.writeObject(output, start, this)
      output.writeBoolean(end.isDefined)
      if(end.isDefined)
        kryo.writeObject(output, end.get, this)

  }

  override def read(kryo: Kryo, input: Input, dType: Class[TemporalExpression]): TemporalExpression = {
    input.readByte() match {
      case TemporalSerializer.INSTANT =>
        val l = input.readLong(true)
        Instant(l)
      case TemporalSerializer.INTERVAL =>
        val start = kryo.readObject(input, classOf[Instant], this)
        val hasEnd = input.readBoolean()
        val end: Option[Instant] = if(hasEnd) {
          val theEnd = kryo.readObject(input, classOf[Instant], this)
          Some(theEnd)
        }
        else {
          None
        }
        Interval(start, end)

    }
  }
}

class STObjectSerializer extends Serializer[STObject] {

  val geometrySerializer = new GeometrySerializer()
  val temporalSerializer = new TemporalSerializer
  override def write(kryo: Kryo, output: Output, obj: STObject): Unit = {
    kryo.writeObject(output, obj.getGeo, geometrySerializer)

    output.writeBoolean(obj.getTemp.isDefined)
    if(obj.getTemp.isDefined)
      kryo.writeObject(output, obj.getTemp.get, temporalSerializer)
  }

  override def read(kryo: Kryo, input: Input, dType: Class[STObject]): STObject = {
    val geo = kryo.readObject(input, classOf[Geometry], geometrySerializer)

    val time = if(input.readBoolean()) {
      val t = kryo.readObject(input, classOf[TemporalExpression], temporalSerializer)
      Some(t)
    } else
      None

    STObject(geo, time)
  }
}

class StarkSerializer extends Serializer[(STObject, Any)] {

  val soSerializer = new STObjectSerializer

  override def write(kryo: Kryo, output: Output, obj: (STObject, Any)): Unit = {
    kryo.writeObject(output, obj._1, soSerializer)
    kryo.writeClassAndObject(output, obj._2)
  }

  override def read(kryo: Kryo, input: Input, dType: Class[(STObject,Any)]): (STObject,Any) = {
    val so = kryo.readObject(input, classOf[STObject], soSerializer)
    val payload = kryo.readClassAndObject(input)

    (so, payload)
  }
}