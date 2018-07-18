/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.contrib.cats

import quasar.contrib.scalaz.MonadState_

import cats.Applicative
import cats.data.StateT

object stateT {
  implicit def catsStateTMonadState_[F[_]: Applicative, S]: MonadState_[StateT[F, S, ?], S] =
    new MonadState_[StateT[F, S, ?], S] {
      def get = StateT.get[F, S]
      def put(s: S) = StateT.set[F, S](s)
    }
}
