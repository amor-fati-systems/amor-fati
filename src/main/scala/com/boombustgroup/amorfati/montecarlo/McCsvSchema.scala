package com.boombustgroup.amorfati.montecarlo

/** Backward-compatible CSV schema facade over the shared delimited-text
  * contract. Existing CSV outputs keep their names while TSV outputs can use
  * [[DelimitedTextSchema]] directly.
  */
private[amorfati] final class McCsvSchema[-Row] private (
    val asDelimitedTextSchema: DelimitedTextSchema[Row],
):
  def header: String =
    asDelimitedTextSchema.header

  def render(row: Row): String =
    asDelimitedTextSchema.render(row)

  def contramap[Source](f: Source => Row): McCsvSchema[Source] =
    McCsvSchema.fromDelimitedTextSchema(asDelimitedTextSchema.contramap(f))

private[amorfati] object McCsvSchema:
  def apply[Row](header: String, render: Row => String): McCsvSchema[Row] =
    fromDelimitedTextSchema(DelimitedTextSchema(header, render))

  def fromDelimitedTextSchema[Row](schema: DelimitedTextSchema[Row]): McCsvSchema[Row] =
    new McCsvSchema(schema)
