/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.fs

import scala.Predef.$conforms
import slamdata.Predef._
import quasar.{Data, DataArbitrary}
import quasar.contrib.pathy._
import quasar.contrib.scalaz.stream._
import quasar.fp._

import pathy.scalacheck.PathyArbitrary._
import scalaz._, Scalaz._

class ReadFileSpec extends quasar.Qspec with FileSystemFixture {
  import DataArbitrary._

  "ReadFile" should {
    "scan should read data until an empty vector is received" >> prop {
      (f: AFile, xs: Vector[Data]) =>

      val doRead = read.scanAll(f).runLogCatch.run

      Mem.run(doRead).eval(InMemory.InMemState.fromFiles(Map(f -> xs))) must_=== xs.right.right
    }.set(maxSize = 10)

    "scan should automatically close the read handle when terminated early" >> prop {
      (f: AFile, xs: Vector[Data]) => xs.nonEmpty ==> {
        val n = xs.length / 2
        val p = write.append(f, xs.toProcess).drain ++ read.scanAll(f).take(n)

        Mem.run(p.runLogCatch.run).run(emptyMem).leftMap(_.rm) must_===
          ((Map.empty, (xs take n).right.right))
      }
    }.set(maxSize = 10)
  }
}
