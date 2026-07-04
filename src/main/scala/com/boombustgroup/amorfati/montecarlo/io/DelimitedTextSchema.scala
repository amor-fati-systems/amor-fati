package com.boombustgroup.amorfati.montecarlo.io

/** Shared delimited-text row contract used by generated TSV outputs. */
private[amorfati] final case class DelimitedTextSchema[-Row](
    header: String,
    render: Row => String,
):
  def contramap[Source](f: Source => Row): DelimitedTextSchema[Source] =
    DelimitedTextSchema(
      header = header,
      render = source => render(f(source)),
    )

private[amorfati] object DelimitedTextSchema:
  def fromCells[Row](
      columns: Vector[String],
      format: DelimitedTextFormat,
  )(cells: Row => Vector[String]): DelimitedTextSchema[Row] =
    DelimitedTextSchema(
      header = format.header(columns),
      render = row =>
        val values = cells(row)
        require(
          values.length == columns.length,
          s"${format.label} row has ${values.length} cells, expected ${columns.length}",
        )
        format.join(values),
    )
