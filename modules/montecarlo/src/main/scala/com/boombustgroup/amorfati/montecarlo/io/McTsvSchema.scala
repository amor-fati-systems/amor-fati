package com.boombustgroup.amorfati.montecarlo.io

import com.boombustgroup.amorfati.tsv.{TsvFormat, TsvSchema}

/** Runtime Monte Carlo TSV schema facade over the shared TSV contract. */
private[amorfati] final class McTsvSchema[-Row] private (
    val asTsvSchema: TsvSchema[Row],
):
  def header: String =
    asTsvSchema.header

  def render(row: Row): String =
    asTsvSchema.render(row)

  def contramap[Source](f: Source => Row): McTsvSchema[Source] =
    McTsvSchema.fromTsvSchema(asTsvSchema.contramap(f))

private[amorfati] object McTsvSchema:
  def header(columns: String*): String =
    TsvFormat.header(columns)

  def apply[Row](header: String, render: Row => String): McTsvSchema[Row] =
    fromTsvSchema(TsvSchema(header, render))

  def fromTsvSchema[Row](schema: TsvSchema[Row]): McTsvSchema[Row] =
    new McTsvSchema(schema)
