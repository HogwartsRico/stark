package org.apache.spark.sql.raster

import dbis.stark.raster.Tile

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, UnsafeArrayData}
import org.apache.spark.sql.types._

@SQLUserDefinedType(udt = classOf[TileUDT])
private[sql] class TileUDT extends UserDefinedType[Tile] {
  override def typeName = "tile"

  override final def sqlType: StructType = _sqlType

  override def serialize(obj: Tile): InternalRow = {
    val row = new GenericInternalRow(5)
    row.setDouble(0, obj.ulx)
    row.setDouble(1, obj.uly)
    row.setInt(2, obj.width)
    row.setInt(3, obj.height)
    row.update(4, UnsafeArrayData.fromPrimitiveArray(obj.data))
    row
  }

  override def deserialize(datum: Any): Tile = {
    datum match {
      case row: InternalRow =>
        require(row.numFields == 5,
          s"TileUDT.deserialize given row with length ${row.numFields} but requires length == 5")
        val ulx = row.getDouble(0)
        val uly = row.getDouble(1)
        val width = row.getInt(2)
        val height = row.getInt(3)
        val data = row.getArray(4).toByteArray()
        new Tile(ulx, uly, width, height, data)
    }
  }

  override def pyUDT: String = "pyspark.stark.raster.TileUDT"

  override def userClass: Class[Tile] = classOf[Tile]

  private[spark] override def asNullable: TileUDT = this

  private[this] val _sqlType = {
    StructType(Seq(
      StructField("ulx", DoubleType, nullable = false),
      StructField("uly", DoubleType, nullable = false),
      StructField("width", IntegerType, nullable = false),
      StructField("height", IntegerType, nullable = false),
      StructField("values", ArrayType(ByteType, containsNull = false), nullable = true)))
  }
}

case object TileUDT extends TileUDT {
  println("register TileUDT")
  UDTRegistration.register(classOf[Tile].getName, classOf[TileUDT].getName)
}