package edu.arizona.sista.odin.impl

import edu.arizona.sista.struct.Interval
import edu.arizona.sista.processors.Document
import edu.arizona.sista.odin._

trait Extractor {
  def name: String
  def label: String
  def priority: Priority
  def keep: Boolean  // should we keep mentions generated by this extractor?
  def action: ReflectedAction

  def findAllIn(sent: Int, doc: Document, state: State): Seq[Mention]

  def findAllIn(doc: Document, state: State): Seq[Mention] = for {
    i <- 0 until doc.sentences.size
    m <- findAllIn(i, doc, state)
  } yield m

  def startsAt: Int = priority match {
    case ExactPriority(i) => i
    case IntervalPriority(start, end) => start
    case FromPriority(from) => from
  }
}

class TokenExtractor(val name: String,
                     val label: String,
                     val priority: Priority,
                     val keep: Boolean,
                     val action: ReflectedAction,
                     val pattern: TokenPattern) extends Extractor {

  def findAllIn(sent: Int, doc: Document, state: State): Seq[Mention] = {
    val results = pattern.findAllIn(sent, doc, state)
    val mentions = for (r <- results) yield mkMention(r, sent, doc)
    action(mentions, state)
  }

  def mkMention(r: TokenPattern.Result, sent: Int, doc: Document): Mention =
    r.groups.keys find (_ equalsIgnoreCase "trigger") match {
      case Some(triggerKey) =>
        // result has a trigger, create an EventMention
        val trigger = new TextBoundMention(label, r.groups(triggerKey), sent, doc, keep, name)
        val groups = r.groups - triggerKey mapValues (i => Seq(new TextBoundMention(label, i, sent, doc, keep, name)))
        val mentions = r.mentions mapValues (Seq(_))
        val args = groups ++ mentions
        new EventMention(label, trigger, args, sent, doc, keep, name)
      case None if r.groups.nonEmpty || r.mentions.nonEmpty =>
        // result has arguments and no trigger, create a RelationMention
        val groups = r.groups mapValues (i => Seq(new TextBoundMention(label, i, sent, doc, keep, name)))
        val mentions = r.mentions mapValues (Seq(_))
        val args = groups ++ mentions
        new RelationMention(label, args, sent, doc, keep, name)
      case None =>
        // result has no arguments, create a TextBoundMention
        new TextBoundMention(label, r.interval, sent, doc, keep, name)
    }
}

class DependencyExtractor(val name: String,
                          val label: String,
                          val priority: Priority,
                          val keep: Boolean,
                          val action: ReflectedAction,
                          val pattern: DependencyPattern) extends Extractor {

  def findAllIn(sent: Int, doc: Document, state: State): Seq[Mention] = {
    val mentions = pattern.getMentions(sent, doc, state, label, keep, name)
    action(mentions, state)
  }
}
