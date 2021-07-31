package run.cosy.ldp

import akka.http.scaladsl.model.Uri
import cats.implicits.*
import cats.laws.discipline.FunctorTests
import munit.DisciplineSuite


class CmdLawTests extends DisciplineSuite {
	checkAll("Tree.FunctorLaws", FunctorTests[SolidCmd].functor[Int, Int, String])
}

import org.scalacheck.{Arbitrary, Gen}
import run.cosy.ldp.SolidCmd.*

object arbitraries {
	implicit def arbTree[A: Arbitrary]: Arbitrary[SolidCmd[A]] =
		Arbitrary(Gen.oneOf((for {
			e <- Arbitrary.arbitrary[A]
		} yield Get(Uri("https://bblfish.net/"), )))
		)
}