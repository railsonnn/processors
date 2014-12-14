package edu.arizona.sista.matcher

import scala.util.parsing.combinator._
import edu.arizona.sista.processors.Document

trait TokenConstraintParsers extends LiteralParsers {
  def tokenConstraint: Parser[TokenConstraint] = "[" ~> disjunctiveConstraint <~ "]"

  def wordConstraint: Parser[TokenConstraint] = stringMatcher ^^ {
    case matcher => new WordConstraint(matcher)
  }

  def disjunctiveConstraint: Parser[TokenConstraint] =
    conjunctiveConstraint ~ rep("|" ~> conjunctiveConstraint) ^^ {
      case first ~ rest => (first /: rest) {
        case (lhs, rhs) => new DisjunctiveConstraint(lhs, rhs)
      }
    }

  def conjunctiveConstraint: Parser[TokenConstraint] =
    negatedConstraint ~ rep("&" ~> negatedConstraint) ^^ {
      case first ~ rest => (first /: rest) {
        case (lhs, rhs) => new ConjunctiveConstraint(lhs, rhs)
      }
    }

  def negatedConstraint: Parser[TokenConstraint] = opt("!") ~ atomicConstraint ^^ {
    case None ~ constraint => constraint
    case Some("!") ~ constraint => new NegatedConstraint(constraint)
  }

  def atomicConstraint: Parser[TokenConstraint] = fieldConstraint | "(" ~> disjunctiveConstraint <~ ")"

  def fieldConstraint: Parser[TokenConstraint] = ident ~ "=" ~ stringMatcher ^^ {
    case "word" ~ _ ~ matcher => new WordConstraint(matcher)
    case "lemma" ~ _ ~ matcher => new LemmaConstraint(matcher)
    case "tag" ~ _ ~ matcher => new TagConstraint(matcher)
    case "entity" ~ _ ~ matcher => new EntityConstraint(matcher)
    case "chunk" ~ _ ~ matcher => new ChunkConstraint(matcher)
    case "incoming" ~ _ ~ matcher => new IncomingConstraint(matcher)
    case "outgoing" ~ _ ~ matcher => new OutgoingConstraint(matcher)
    case "mention" ~ _ ~ matcher => new MentionConstraint(matcher)
    case _ => sys.error("unrecognized token field")
  }
}

sealed trait TokenConstraint {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean
  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int]
}

class WordConstraint(matcher: StringMatcher) extends TokenConstraint with Values {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    matcher matches word(tok, sent, doc)

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] =
    matcher filter words(tokens, sent, doc)
}

class LemmaConstraint(matcher: StringMatcher) extends TokenConstraint with Values {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    matcher matches lemma(tok, sent, doc)

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] =
    matcher filter lemmas(tokens, sent, doc)
}

class TagConstraint(matcher: StringMatcher) extends TokenConstraint with Values {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    matcher matches tag(tok, sent, doc)

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] =
    matcher filter tags(tokens, sent, doc)
}

class EntityConstraint(matcher: StringMatcher) extends TokenConstraint with Values {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    matcher matches entity(tok, sent, doc)

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] =
    matcher filter entities(tokens, sent, doc)
}

class ChunkConstraint(matcher: StringMatcher) extends TokenConstraint with Values {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    matcher matches chunk(tok, sent, doc)

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] =
    matcher filter chunks(tokens, sent, doc)
}

class IncomingConstraint(matcher: StringMatcher) extends TokenConstraint with Dependencies {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    incoming(tok, sent, doc) exists matcher.matches

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] = {
    val edges = incomingEdges(sent, doc)
    tokens filter (tok => (edges.isDefinedAt(tok) && matcher.filter(edges(tok)).nonEmpty))
  }
}

class OutgoingConstraint(matcher: StringMatcher) extends TokenConstraint with Dependencies {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    outgoing(tok, sent, doc) exists matcher.matches

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] = {
    val edges = outgoingEdges(sent, doc)
    tokens filter (tok => (edges.isDefinedAt(tok) && matcher.filter(edges(tok)).nonEmpty))
  }
}

class MentionConstraint(matcher: StringMatcher) extends TokenConstraint {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean = state match {
    case None => sys.error("can't match mentions without state")
    case Some(state) => state.mentionsFor(sent, tok) flatMap (_.allLabels) exists matcher.matches
  }

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] = state match {
    case None => sys.error("can't match mentions without state")
    case Some(state) => tokens filter { t =>
      state.mentionsFor(sent, t) exists { m =>
        val indicesAndValues = m.allLabels.toSeq.zipWithIndex map (li => (li._2, li._1))
        matcher.filter(indicesAndValues).nonEmpty
      }
    }
  }
}

class NegatedConstraint(constraint: TokenConstraint) extends TokenConstraint {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    !constraint.matches(tok, sent, doc, state)

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] =
    tokens diff constraint.filter(tokens, sent, doc, state)
}

class ConjunctiveConstraint(lhs: TokenConstraint, rhs: TokenConstraint) extends TokenConstraint {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    lhs.matches(tok, sent, doc, state) && rhs.matches(tok, sent, doc, state)

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] =
    lhs.filter(tokens, sent, doc, state) intersect rhs.filter(tokens, sent, doc, state)
}

class DisjunctiveConstraint(lhs: TokenConstraint, rhs: TokenConstraint) extends TokenConstraint {
  def matches(tok: Int, sent: Int, doc: Document, state: Option[State]): Boolean =
    lhs.matches(tok, sent, doc, state) || rhs.matches(tok, sent, doc, state)

  def filter(tokens: Seq[Int], sent: Int, doc: Document, state: Option[State]): Seq[Int] =
    (lhs.filter(tokens, sent, doc, state) union rhs.filter(tokens, sent, doc, state)).distinct
}
