package com.boombustgroup.amorfati.montecarlo

private[amorfati] final case class DelimitedTextFormat(label: String, delimiter: Char):
  private val delimiterString = delimiter.toString

  def header(columns: Iterable[String]): String =
    join(columns)

  def join(values: Iterable[String]): String =
    values.iterator.map(escape).mkString(delimiterString)

  def escape(value: String): String =
    if value.exists(ch => ch == delimiter || ch == '"' || ch == '\n' || ch == '\r') then "\"" + value.replace("\"", "\"\"") + "\""
    else value

private[amorfati] object DelimitedTextFormat:
  val Tsv: DelimitedTextFormat = DelimitedTextFormat("TSV", '\t')
