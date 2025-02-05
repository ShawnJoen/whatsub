package whatsub.charset

import cats.*
import cats.effect.Resource
import cats.syntax.all.*
import effectie.cats.*
import effectie.cats.Effectful.*
import effectie.cats.syntax.error.*
import extras.cats.syntax.all.*
import whatsub.MCancel

import java.io.File
import scala.io.Source

/** @author Kevin Lee
  * @since 2021-08-15
  */
trait ConvertCharset[F[*], A, B] {
  def convert(from: ConvertCharset.From, to: ConvertCharset.To)(input: A)(
    f: String => F[B],
  ): F[Either[CharsetConvertError, B]]
}

object ConvertCharset {

  final val EmptyCharRegEx = "[\uFEFF-\uFFFF]"

  def convertStringCharset[F[*]: Fx: Monad: CanCatch, B]: ConvertCharset[F, String, B] =
    new ConvertCharset[F, String, B] {
      override def convert(from: From, to: To)(input: String)(f: String => F[B]): F[Either[CharsetConvertError, B]] =
        (for {
          converted <- effectOf(
                         new String(
                           input
                             .replaceAll(EmptyCharRegEx, "")
                             .getBytes(from.javaCharset),
                           to.javaCharset,
                         ),
                       )
                         .catchNonFatal(err => CharsetConvertError.Conversion(from, to, input, err))
                         .eitherT
          result    <- f(converted)
                         .catchNonFatal(err => CharsetConvertError.Consumption(converted, err))
                         .eitherT
        } yield result).value
    }

  def convertFileCharset[F[*]: Monad: MCancel: Fx: CanCatch, B: Monoid]: ConvertCharset[F, File, B] =
    new ConvertCharset[F, File, B] {

      override def convert(from: From, to: To)(input: File)(f: String => F[B]): F[Either[CharsetConvertError, B]] =
        Resource
          .make(effectOf(Source.fromFile(input, from.render)))(source => effectOf(source.close()))
          .use { source =>
            source
              .getLines
              .to(LazyList)
              .traverse { line =>
                (for {
                  converted <- effectOf(
                                 new String(
                                   line
                                     .replaceAll(EmptyCharRegEx, "")
                                     .getBytes(Charset.Utf8.value),
                                   to.javaCharset,
                                 ),
                               ).catchNonFatal(err =>
                                 CharsetConvertError.Conversion(from, to, s"File at ${input.getCanonicalPath}", err),
                               ).eitherT
                  result    <- f(converted)
                                 .catchNonFatal(err => CharsetConvertError.Consumption(converted, err))
                                 .eitherT
                } yield result).value
              }
              .map(
                _.foldLeft(Monoid[B].empty.asRight)((acc, b) => acc.flatMap(accB => b.map(accB |+| _))),
              )
          }

    }

  type From = From.From
  object From {
    opaque type From = Charset
    def apply(from: Charset): From = from

    given fromCanEqual: CanEqual[From, From] = CanEqual.derived

    extension (from: From) {
      def value: Charset = from

      def javaCharset: java.nio.charset.Charset = from.value.value

      def render: String = from.javaCharset.name
    }
  }

  type To = To.To
  object To {
    opaque type To = Charset
    def apply(to: Charset): To = to

    given toCanEqual: CanEqual[To, To] = CanEqual.derived

    extension (to: To) {
      def value: Charset = to

      def javaCharset: java.nio.charset.Charset = to.value.value

      def render: String = to.javaCharset.name
    }
  }

}
