package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*

/** Validated aligned view over bank operational rows and their ledger-owned
  * financial stock rows.
  *
  * Public `Banking.*` methods keep their stable `(banks, financialStocks)`
  * signatures. Domain modules cross that API boundary once through this type,
  * then operate on a single aligned surface instead of repeatedly zipping
  * parallel vectors.
  */
private[banking] final class BankRows private (
    val banks: Vector[BankState],
    val financialStocks: Vector[BankFinancialStocks],
):
  def length: Int = banks.length

  def map[A](f: (BankState, BankFinancialStocks) => A): Vector[A] =
    val out = Vector.newBuilder[A]
    var i   = 0
    while i < length do
      out += f(banks(i), financialStocks(i))
      i += 1
    out.result()

  def mapWithIndex[A](f: (Int, BankState, BankFinancialStocks) => A): Vector[A] =
    val out = Vector.newBuilder[A]
    var i   = 0
    while i < length do
      out += f(i, banks(i), financialStocks(i))
      i += 1
    out.result()

  def filter(p: (BankState, BankFinancialStocks) => Boolean): Vector[(BankState, BankFinancialStocks)] =
    val out = Vector.newBuilder[(BankState, BankFinancialStocks)]
    var i   = 0
    while i < length do
      val b      = banks(i)
      val stocks = financialStocks(i)
      if p(b, stocks) then out += ((b, stocks))
      i += 1
    out.result()

  def foldLeft[A](init: A)(f: (A, BankState, BankFinancialStocks) => A): A =
    var acc = init
    var i   = 0
    while i < length do
      acc = f(acc, banks(i), financialStocks(i))
      i += 1
    acc

  def forallBanks(p: BankState => Boolean): Boolean =
    var ok = true
    var i  = 0
    while i < length && ok do
      ok = p(banks(i))
      i += 1
    ok

object BankRows:
  def from(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], owner: String): BankRows =
    require(
      banks.length == financialStocks.length,
      s"$owner requires aligned banks and financial stocks, got ${banks.length} banks and ${financialStocks.length} stock rows",
    )
    new BankRows(banks, financialStocks)
