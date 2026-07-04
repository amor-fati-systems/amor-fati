package com.boombustgroup.amorfati.tsv

/** Shared row contract used by generated TSV outputs. */
private[amorfati] final case class TsvSchema[-Row](
    header: String,
    render: Row => String,
):
  def contramap[Source](f: Source => Row): TsvSchema[Source] =
    TsvSchema(
      header = header,
      render = source => render(f(source)),
    )

private[amorfati] object TsvSchema:
  def fromCells[Row](columns: Vector[String])(cells: Row => Vector[String]): TsvSchema[Row] =
    TsvSchema(
      header = TsvFormat.header(columns),
      render = row =>
        val values = cells(row)
        require(
          values.length == columns.length,
          s"${TsvFormat.Label} row has ${values.length} cells, expected ${columns.length}",
        )
        TsvFormat.join(values),
    )
