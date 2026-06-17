package com.boombustgroup.amorfati.montecarlo

/** Runtime TSV schema facade over the shared delimited-text contract. */
private[amorfati] final class McTsvSchema[-Row] private (
    val asDelimitedTextSchema: DelimitedTextSchema[Row],
):
  def header: String =
    asDelimitedTextSchema.header

  def render(row: Row): String =
    asDelimitedTextSchema.render(row)

  def contramap[Source](f: Source => Row): McTsvSchema[Source] =
    McTsvSchema.fromDelimitedTextSchema(asDelimitedTextSchema.contramap(f))

private[amorfati] object McTsvSchema:
  def header(columns: String*): String =
    DelimitedTextFormat.Tsv.header(columns)

  def apply[Row](header: String, render: Row => String): McTsvSchema[Row] =
    fromDelimitedTextSchema(DelimitedTextSchema(header, render))

  def fromDelimitedTextSchema[Row](schema: DelimitedTextSchema[Row]): McTsvSchema[Row] =
    new McTsvSchema(schema)
