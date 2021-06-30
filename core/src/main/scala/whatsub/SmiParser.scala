package whatsub

import cats.effect.*
import cats.parse.Rfc5234.*
import cats.parse.{Parser as P, Parser0 as P0, *}
import cats.syntax.all.*

object SmiParser {

  val NoNewlineChars = (0.toChar to Char.MaxValue).filter(c => c != '\n' && c != '\r')

  final case class StartAndLine(start: Long, line: String)

  final case class SmiLine(line: String)

  val spaceP      = wsp
  val newlineP    = (crlf | cr | lf)
  val samiSatartP = (P.ignoreCase("<SAMI>") <* (lwsp.string.? ~ newlineP.?)).map(s => SmiComponent.SamiStart)
  val styleP      = (spaceP | P.string("<!--") | P.string("-->") | (alpha | digit | P.charIn(".{}-:;")).rep.string)
  val headP       =
    (
      (
        (P.ignoreCase("<HEAD>") ~ lwsp.? ~ newlineP.? ~ P.ignoreCase("<TITLE>")) *>
          (alpha | digit | spaceP | P.charIn(".-_")).rep.string <*
          (P.ignoreCase("</TITLE>") ~ lwsp.? ~ newlineP.? ~ P.ignoreCase(
            """<STYLE TYPE="text/css">"""
          ) ~ lwsp.? ~ newlineP.?)
      ) ~ ((styleP.rep.string.backtrack ~ newlineP.?).rep.string <*
        (P.ignoreCase("</STYLE>") ~ lwsp.? ~ newlineP.? ~ P.ignoreCase("</HEAD>") ~ lwsp.? ~ newlineP.?))
    )
      .map {
        case (title, style) =>
          SmiComponent.Head(SmiComponent.Title(title))
      }

  val bodyStartP = (P.ignoreCase("<BODY>") <* (lwsp.string.? ~ newlineP.?)).map(_ => SmiComponent.BodyStart)

  val bodyLine = (
    (
      P.ignoreCase("<SYNC Start=") *> digit.rep.string <* (P.ignoreCase("><P Class=") ~ alpha.rep.string ~ P.ignoreCase(
        ">"
      ))
    ) ~ ((lwsp.string.?) *> P.charIn(SmiParser.NoNewlineChars).rep.string <* (lwsp.string.? ~ newlineP))
  )
    .map { case (startTime, line) => StartAndLine(startTime.toLong, line) }

  val bodyEndP = (P.ignoreCase("</BODY>") <* (lwsp.string.? ~ newlineP.?)).map(_ => SmiComponent.BodyEnd)
  val samiEndP = (P.ignoreCase("</SAMI>") <* (lwsp.string.? ~ newlineP.?)).map(_ => SmiComponent.SamiEnd)

  def parseSmiStart(lines: String, acc: List[SmiComponent]): List[SmiComponent] =
    samiSatartP.parse(lines) match {
      case Right((remaining, start)) =>
        parseSmiHead(remaining, start :: acc)

      case Left(_) =>
        acc
    }

  def parseSmiHead(lines: String, acc: List[SmiComponent]): List[SmiComponent] =
    headP.parse(lines) match {
      case Right((remaining, head)) =>
        parseBodyStart(remaining, head :: acc)

      case Left(_) =>
        acc
    }

  def parseBodyStart(lines: String, acc: List[SmiComponent]): List[SmiComponent] =
    bodyStartP.parse(lines) match {
      case Right((remaining, bodyStart)) =>
        parseBody(remaining, bodyStart :: acc)

      case Left(_) =>
        acc
    }

  def parseBody(lines: String, acc: List[SmiComponent]): List[SmiComponent] = {

    def parseNextLine(lines: String, previous: Option[StartAndLine], acc: List[SmiComponent]): List[SmiComponent] =
      bodyEndP.parse(lines) match {
        case Right((remaining, bodyEnd)) =>
          parseSamiEnd(remaining, bodyEnd :: acc)

        case Left(_) =>
          bodyLine.parse(lines) match {
            case Right((remaining, StartAndLine(time, line))) =>
              previous match {
                case Some(StartAndLine(startTime, theLine)) =>
                  parseNextLine(
                    remaining,
                    none,
                    SmiComponent.BodyLine(
                      SmiComponent.Milliseconds(startTime.toLong),
                      SmiComponent.Milliseconds(time.toLong),
                      SmiComponent.Line(theLine)
                    ) :: acc
                  )

                case None =>
                  parseNextLine(
                    remaining,
                    StartAndLine(time.toLong, line).some,
                    acc
                  )
              }

            case Left(err) =>
              acc
          }
      }

    parseNextLine(lines, none, acc)
  }

  def parseSamiEnd(lines: String, acc: List[SmiComponent]): List[SmiComponent] =
    samiEndP.parse(lines) match {
      case Right((remining, samiEnd)) =>
        SmiComponent.SamiEnd :: acc

      case Left(_) =>
        acc
    }


  private def fromSmiComponents(smiComponents: List[SmiComponent]): Smi =
    smiComponents.foldRight((none[Smi.Title], List.empty[Smi.SmiLine])) {
      case (smiComponent, (maybeTitle, acc)) =>
        smiComponent match {
          case SmiComponent.BodyLine(start, end, line) =>
            (
              maybeTitle,
              Smi.SmiLine(
                Smi.Start(start.milliseconds),
                Smi.End(end.milliseconds),
                Smi.Line(line.line)
              ) :: acc
            )

          case SmiComponent.Head(title) =>
            (Smi.Title(title.title).some, acc)

          case SmiComponent.SamiStart | SmiComponent.BodyStart | SmiComponent.BodyEnd | SmiComponent.SamiEnd =>
            (maybeTitle, acc)
        }
    } match {
      case (Some(title), lines) =>
        Smi(title, lines)

      case (None, lines) =>
        Smi(Smi.Title(""), lines)
    }

  def parse(lines: String): Smi =
    fromSmiComponents(parseSmiStart(lines, List.empty).reverse)

}
