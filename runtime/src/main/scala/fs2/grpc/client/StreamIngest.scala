/*
 * Copyright (c) 2018 Gary Coady / Fs2 Grpc Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package grpc
package client

import cats.implicits._
import cats.effect.Concurrent
import cats.effect.Deferred
import fs2.concurrent.Channel

private[client] trait StreamIngest[F[_], T] {
  def onMessage(msg: T): F[Unit]
  def onClose(status: GrpcStatus): F[Unit]
  def messages: Stream[F, T]
}

private[client] object StreamIngest {

  def apply[F[_]: Concurrent, T](
      request: Int => F[Unit],
      prefetchN: Int
  ): F[StreamIngest[F, T]] =
    Deferred[F, GrpcStatus].flatMap { gate =>
      Channel.bounded[F, T](prefetchN).map(ch => create(request, ch, gate))
    }

  def create[F[_], T](
      request: Int => F[Unit],
      channel: Channel[F, T],
      gate: Deferred[F, GrpcStatus]
  )(implicit F: Concurrent[F]): StreamIngest[F, T] = new StreamIngest[F, T] {

    def onMessage(msg: T): F[Unit] =
      channel.send(msg).void

    def onClose(status: GrpcStatus): F[Unit] =
      channel.close *> gate.complete(status).void

    val messages: Stream[F, T] = {
      val close = gate.get.flatMap { case GrpcStatus(status, trailers) =>
        F.raiseError(status.asRuntimeException(trailers)).whenA(!status.isOk)
      }

      channel.stream.chunks
        .flatMap { chunk =>
          Stream.exec(request(chunk.size)) ++ Stream.chunk(chunk)
        }
        .onFinalize(close)
    }

  }

}
