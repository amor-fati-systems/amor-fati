package com.boombustgroup.amorfati.montecarlo

/** Shared delimited-text row contract used by CSV and TSV outputs. */
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
      render = row => format.join(cells(row)),
    )
