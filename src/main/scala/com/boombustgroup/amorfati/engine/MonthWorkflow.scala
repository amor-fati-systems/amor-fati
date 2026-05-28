package com.boombustgroup.amorfati.engine

/** Minimal identity monad for deterministic month-step workflows.
  *
  * The engine step is pure and synchronous, so this intentionally carries no
  * effects, no hidden state, and no error channel. Its purpose is
  * architectural: make phase ordering readable as a `for`-comprehension while
  * keeping runtime representation equal to the wrapped value.
  */
object MonthWorkflow:

  opaque type Program[+A] = A

  inline def pure[A](value: A): Program[A] =
    value

  inline def run[A](program: Program[A]): A =
    program

  extension [A](program: Program[A])
    inline def map[B](f: A => B): Program[B] =
      f(program)

    inline def flatMap[B](f: A => Program[B]): Program[B] =
      f(program)
