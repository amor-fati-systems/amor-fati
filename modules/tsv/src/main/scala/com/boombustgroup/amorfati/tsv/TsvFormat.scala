package com.boombustgroup.amorfati.tsv

private[amorfati] object TsvFormat:
  val Label: String = "TSV"

  private[tsv] val Delimiter: Char = '\t'
  private val delimiterString      = Delimiter.toString

  def header(columns: Iterable[String]): String =
    join(columns)

  def join(values: Iterable[String]): String =
    values.iterator.map(escape).mkString(delimiterString)

  def escape(value: String): String =
    if value.exists(ch => ch == Delimiter || ch == '"' || ch == '\n' || ch == '\r') then "\"" + value.replace("\"", "\"\"") + "\""
    else value
