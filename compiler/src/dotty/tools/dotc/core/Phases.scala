package dotty.tools
package dotc
package core

import Periods._
import Contexts._
import dotty.tools.backend.jvm.GenBCode
import DenotTransformers._
import Denotations._
import Decorators._
import config.Printers.config
import scala.collection.mutable.ListBuffer
import dotty.tools.dotc.transform.MegaPhase._
import dotty.tools.dotc.transform._
import Periods._
import typer.{FrontEnd, RefChecks}
import typer.ImportInfo.withRootImports
import ast.tpd
import scala.annotation.internal.sharable

object Phases {

  inline def phaseOf(id: PhaseId)(using Context): Phase =
    ctx.base.phases(id)

  @sharable object NoPhase extends Phase {
    override def exists: Boolean = false
    def phaseName: String = "<no phase>"
    def run(using Context): Unit = unsupported("run")
    def transform(ref: SingleDenotation)(using Context): SingleDenotation = unsupported("transform")
  }

  trait PhasesBase {
    this: ContextBase =>

    // drop NoPhase at beginning
    def allPhases: Array[Phase] = (if (fusedPhases.nonEmpty) fusedPhases else phases).tail

    object SomePhase extends Phase {
      def phaseName: String = "<some phase>"
      def run(using Context): Unit = unsupported("run")
    }

    /** A sentinel transformer object */
    class TerminalPhase extends DenotTransformer {
      def phaseName: String = "terminal"
      def run(using Context): Unit = unsupported("run")
      def transform(ref: SingleDenotation)(using Context): SingleDenotation =
        unsupported("transform")
      override def lastPhaseId(using Context): Int = id
    }

    final def phasePlan: List[List[Phase]] = this.phasesPlan
    final def setPhasePlan(phasess: List[List[Phase]]): Unit = this.phasesPlan = phasess

    /** Squash TreeTransform's beloning to same sublist to a single TreeTransformer
      * Each TreeTransform gets own period,
      * whereas a combined TreeTransformer gets period equal to union of periods of it's TreeTransforms
      */
    final def fusePhases(phasess: List[List[Phase]],
                           phasesToSkip: List[String],
                           stopBeforePhases: List[String],
                           stopAfterPhases: List[String],
                           YCheckAfter: List[String])(using Context): List[Phase] = {
      val fusedPhases = ListBuffer[Phase]()
      var prevPhases: Set[String] = Set.empty
      val YCheckAll = YCheckAfter.contains("all")

      var stop = false

      def isEnabled(p: Phase): Boolean =
        !stop &&
        !stopBeforePhases.contains(p.phaseName) &&
        !phasesToSkip.contains(p.phaseName) &&
        p.isEnabled

      val filteredPhases = phasess.map(_.filter { p =>
        try isEnabled(p)
        finally stop |= stopBeforePhases.contains(p.phaseName) | stopAfterPhases.contains(p.phaseName)
      })

      var i = 0

      while (i < filteredPhases.length) {
        if (filteredPhases(i).nonEmpty) { //could be empty due to filtering
          val filteredPhaseBlock = filteredPhases(i)
          val phaseToAdd =
            if (filteredPhaseBlock.length > 1) {
              for (phase <- filteredPhaseBlock)
                phase match {
                  case p: MiniPhase =>
                    val unmetRequirements = p.runsAfterGroupsOf &~ prevPhases
                    assert(unmetRequirements.isEmpty,
                      s"${phase.phaseName} requires ${unmetRequirements.mkString(", ")} to be in different TreeTransformer")

                  case _ =>
                    assert(false, s"Only tree transforms can be fused, ${phase.phaseName} can not be fused")
                }
              val superPhase = new MegaPhase(filteredPhaseBlock.asInstanceOf[List[MiniPhase]].toArray)
              prevPhases ++= filteredPhaseBlock.map(_.phaseName)
              superPhase
            }
            else { // block of a single phase, no fusion
              val phase = filteredPhaseBlock.head
              prevPhases += phase.phaseName
              phase
            }
          fusedPhases += phaseToAdd
          val shouldAddYCheck = YCheckAfter.containsPhase(phaseToAdd) || YCheckAll
          if (shouldAddYCheck) {
            val checker = new TreeChecker
            fusedPhases += checker
          }
        }

        i += 1
      }
      fusedPhases.toList
    }

    /** Use the following phases in the order they are given.
     *  The list should never contain NoPhase.
     *  if fusion is enabled, phases in same subgroup will be fused to single phase.
     */
    final def usePhases(phasess: List[Phase], fuse: Boolean = true): Unit = {

      val flatPhases = collection.mutable.ListBuffer[Phase]()

      phasess.foreach(p => p match {
        case p: MegaPhase => flatPhases ++= p.miniPhases
        case _ => flatPhases += p
      })

      phases = (NoPhase :: flatPhases.toList ::: new TerminalPhase :: Nil).toArray
      setSpecificPhases()
      var phasesAfter: Set[String] = Set.empty
      nextDenotTransformerId = new Array[Int](phases.length)
      denotTransformers = new Array[DenotTransformer](phases.length)

      var phaseId = 0
      def nextPhaseId = {
        phaseId += 1
        phaseId // starting from 1 as NoPhase is 0
      }

      def checkRequirements(p: Phase) = {
        val unmetPrecedeRequirements = p.runsAfter -- phasesAfter
        assert(unmetPrecedeRequirements.isEmpty,
          s"phase ${p} has unmet requirement: ${unmetPrecedeRequirements.mkString(", ")} should precede this phase")
        phasesAfter += p.phaseName
      }

      var i = 0

      while (i < phasess.length) {
        val phase = phasess(i)
        phase match {
          case p: MegaPhase =>
            val miniPhases = p.miniPhases
            miniPhases.foreach{ phase =>
              checkRequirements(phase)
              phase.init(this, nextPhaseId)}
            p.init(this, miniPhases.head.id, miniPhases.last.id)
          case _ =>
            phase.init(this, nextPhaseId)
            checkRequirements(phase)
        }

        i += 1
      }

      phases.last.init(this, nextPhaseId) // init terminal phase

      i = phases.length
      var lastTransformerId = i
      while (i > 0) {
        i -= 1
        val phase = phases(i)
        phase match {
          case transformer: DenotTransformer =>
            lastTransformerId = i
            denotTransformers(i) = transformer
          case _ =>
        }
        nextDenotTransformerId(i) = lastTransformerId
      }

      if (fuse)
        this.fusedPhases = (NoPhase :: phasess).toArray
      else
        this.fusedPhases = this.phases

      config.println(s"Phases = ${phases.toList}")
      config.println(s"nextDenotTransformerId = ${nextDenotTransformerId.toList}")
    }

    private var myTyperPhase: Phase = _
    private var myPostTyperPhase: Phase = _
    private var mySbtExtractDependenciesPhase: Phase = _
    private var myPicklerPhase: Phase = _
    private var myInliningPhase: Phase = _
    private var myPickleQuotesPhase: Phase = _
    private var myFirstTransformPhase: Phase = _
    private var myCollectNullableFieldsPhase: Phase = _
    private var myRefChecksPhase: Phase = _
    private var myPatmatPhase: Phase = _
    private var myElimRepeatedPhase: Phase = _
    private var myExtensionMethodsPhase: Phase = _
    private var myExplicitOuterPhase: Phase = _
    private var myGettersPhase: Phase = _
    private var myErasurePhase: Phase = _
    private var myElimErasedValueTypePhase: Phase = _
    private var myLambdaLiftPhase: Phase = _
    private var myCountOuterAccessesPhase: Phase = _
    private var myFlattenPhase: Phase = _
    private var myGenBCodePhase: Phase = _

    final def typerPhase: Phase = myTyperPhase
    final def postTyperPhase: Phase = myPostTyperPhase
    final def sbtExtractDependenciesPhase: Phase = mySbtExtractDependenciesPhase
    final def picklerPhase: Phase = myPicklerPhase
    final def inliningPhase: Phase = myInliningPhase
    final def pickleQuotesPhase: Phase = myPickleQuotesPhase
    final def firstTransformPhase: Phase = myFirstTransformPhase
    final def collectNullableFieldsPhase: Phase = myCollectNullableFieldsPhase
    final def refchecksPhase: Phase = myRefChecksPhase
    final def patmatPhase: Phase = myPatmatPhase
    final def elimRepeatedPhase: Phase = myElimRepeatedPhase
    final def extensionMethodsPhase: Phase = myExtensionMethodsPhase
    final def explicitOuterPhase: Phase = myExplicitOuterPhase
    final def gettersPhase: Phase = myGettersPhase
    final def erasurePhase: Phase = myErasurePhase
    final def elimErasedValueTypePhase: Phase = myElimErasedValueTypePhase
    final def lambdaLiftPhase: Phase = myLambdaLiftPhase
    final def countOuterAccessesPhase = myCountOuterAccessesPhase
    final def flattenPhase: Phase = myFlattenPhase
    final def genBCodePhase: Phase = myGenBCodePhase

    private def setSpecificPhases() = {
      def phaseOfClass(pclass: Class[?]) = phases.find(pclass.isInstance).getOrElse(NoPhase)

      myTyperPhase = phaseOfClass(classOf[FrontEnd])
      myPostTyperPhase = phaseOfClass(classOf[PostTyper])
      mySbtExtractDependenciesPhase = phaseOfClass(classOf[sbt.ExtractDependencies])
      myPicklerPhase = phaseOfClass(classOf[Pickler])
      myInliningPhase = phaseOfClass(classOf[Inlining])
      myPickleQuotesPhase = phaseOfClass(classOf[PickleQuotes])
      myFirstTransformPhase = phaseOfClass(classOf[FirstTransform])
      myCollectNullableFieldsPhase = phaseOfClass(classOf[CollectNullableFields])
      myRefChecksPhase = phaseOfClass(classOf[RefChecks])
      myElimRepeatedPhase = phaseOfClass(classOf[ElimRepeated])
      myExtensionMethodsPhase = phaseOfClass(classOf[ExtensionMethods])
      myErasurePhase = phaseOfClass(classOf[Erasure])
      myElimErasedValueTypePhase = phaseOfClass(classOf[ElimErasedValueType])
      myPatmatPhase = phaseOfClass(classOf[PatternMatcher])
      myLambdaLiftPhase = phaseOfClass(classOf[LambdaLift])
      myCountOuterAccessesPhase = phaseOfClass(classOf[CountOuterAccesses])
      myFlattenPhase = phaseOfClass(classOf[Flatten])
      myExplicitOuterPhase = phaseOfClass(classOf[ExplicitOuter])
      myGettersPhase = phaseOfClass(classOf[Getters])
      myGenBCodePhase =  phaseOfClass(classOf[GenBCode])
    }

    final def isAfterTyper(phase: Phase): Boolean = phase.id > typerPhase.id
  }

  abstract class Phase {

    /** A name given to the `Phase` that can be used to debug the compiler. For
     *  instance, it is possible to print trees after a given phase using:
     *
     *  ```bash
     *  $ ./bin/scalac -Xprint:<phaseNameHere> sourceFile.scala
     *  ```
     */
    def phaseName: String

    def isRunnable(using Context): Boolean =
      !ctx.reporter.hasErrors
        // TODO: This might test an unintended condition.
        // To find out whether any errors have been reported during this
        // run one calls `errorsReported`, not `hasErrors`.
        // But maybe changing this would prevent useful phases from running?

    /** If set, allow missing or superfluous arguments in applications
     *  and type applications.
     */
    def relaxedTyping: Boolean = false

    /** If set, implicit search is enabled */
    def allowsImplicitSearch: Boolean = false

    /** List of names of phases that should precede this phase */
    def runsAfter: Set[String] = Set.empty

    /** @pre `isRunnable` returns true */
    def run(using Context): Unit

    /** @pre `isRunnable` returns true */
    def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
      units.map { unit =>
        val unitCtx = ctx.fresh.setPhase(this.start).setCompilationUnit(unit).withRootImports
        run(using unitCtx)
        unitCtx.compilationUnit
      }

    def description: String = phaseName

    /** Output should be checkable by TreeChecker */
    def isCheckable: Boolean = true

    /** Check what the phase achieves, to be called at any point after it is finished.
     */
    def checkPostCondition(tree: tpd.Tree)(using Context): Unit = ()

    /** Is this phase the standard typerphase? True for FrontEnd, but
     *  not for other first phases (such as FromTasty). The predicate
     *  is tested in some places that perform checks and corrections. It's
     *  different from ctx.isAfterTyper (and cheaper to test).
     */
    def isTyper: Boolean = false

    /** Can this transform create or delete non-private members? */
    def changesMembers: Boolean = false

    /** Can this transform change the parents of a class? */
    def changesParents: Boolean = false

    /** Can this transform change the base types of a type? */
    def changesBaseTypes: Boolean = changesParents

    def isEnabled(using Context): Boolean = true

    def exists: Boolean = true

    def initContext(ctx: FreshContext): Unit = ()

    private var myPeriod: Period = Periods.InvalidPeriod
    private var myBase: ContextBase = null
    private var myErasedTypes = false
    private var myFlatClasses = false
    private var myRefChecked = false
    private var myLambdaLifted = false
    private var myPatternTranslated = false

    private var mySameMembersStartId = NoPhaseId
    private var mySameParentsStartId = NoPhaseId
    private var mySameBaseTypesStartId = NoPhaseId

    /** The sequence position of this phase in the given context where 0
     * is reserved for NoPhase and the first real phase is at position 1.
     * -1 if the phase is not installed in the context.
     */
    def id: Int = myPeriod.firstPhaseId

    def period: Period = myPeriod
    def start: Int = myPeriod.firstPhaseId
    def end: Periods.PhaseId = myPeriod.lastPhaseId

    final def erasedTypes: Boolean = myErasedTypes   // Phase is after erasure
    final def flatClasses: Boolean = myFlatClasses   // Phase is after flatten
    final def refChecked: Boolean = myRefChecked     // Phase is after RefChecks
    final def lambdaLifted: Boolean = myLambdaLifted // Phase is after LambdaLift
    final def patternTranslated: Boolean = myPatternTranslated // Phase is after PatternMatcher

    final def sameMembersStartId: Int = mySameMembersStartId
      // id of first phase where all symbols are guaranteed to have the same members as in this phase
    final def sameParentsStartId: Int = mySameParentsStartId
      // id of first phase where all symbols are guaranteed to have the same parents as in this phase
    final def sameBaseTypesStartId: Int = mySameBaseTypesStartId
      // id of first phase where all symbols are guaranteed to have the same base tpyes as in this phase

    protected[Phases] def init(base: ContextBase, start: Int, end: Int): Unit = {
      if (start >= FirstPhaseId)
        assert(myPeriod == Periods.InvalidPeriod, s"phase $this has already been used once; cannot be reused")
      assert(start <= Periods.MaxPossiblePhaseId, s"Too many phases, Period bits overflow")
      myBase = base
      myPeriod = Period(NoRunId, start, end)
      myErasedTypes  = prev.getClass == classOf[Erasure]    || prev.erasedTypes
      myFlatClasses  = prev.getClass == classOf[Flatten]    || prev.flatClasses
      myRefChecked   = prev.getClass == classOf[RefChecks]  || prev.refChecked
      myLambdaLifted = prev.getClass == classOf[LambdaLift] || prev.lambdaLifted
      myPatternTranslated = prev.getClass == classOf[PatternMatcher] || prev.patternTranslated
      mySameMembersStartId = if (changesMembers) id else prev.sameMembersStartId
      mySameParentsStartId = if (changesParents) id else prev.sameParentsStartId
      mySameBaseTypesStartId = if (changesBaseTypes) id else prev.sameBaseTypesStartId
    }

    protected[Phases] def init(base: ContextBase, id: Int): Unit = init(base, id, id)

    final def <=(that: Phase): Boolean =
      exists && id <= that.id

    final def prev: Phase =
      if (id > FirstPhaseId) myBase.phases(start - 1) else NoPhase

    final def next: Phase =
      if (hasNext) myBase.phases(end + 1) else NoPhase

    final def hasNext: Boolean = start >= FirstPhaseId && end + 1 < myBase.phases.length

    final def iterator: Iterator[Phase] =
      Iterator.iterate(this)(_.next) takeWhile (_.hasNext)

    override def toString: String = phaseName
  }

  def typerPhase(using Context): Phase                  = ctx.base.typerPhase
  def postTyperPhase(using Context): Phase              = ctx.base.postTyperPhase
  def sbtExtractDependenciesPhase(using Context): Phase = ctx.base.sbtExtractDependenciesPhase
  def picklerPhase(using Context): Phase                = ctx.base.picklerPhase
  def inliningPhase(using Context): Phase               = ctx.base.inliningPhase
  def pickleQuotesPhase(using Context): Phase           = ctx.base.pickleQuotesPhase
  def firstTransformPhase(using Context): Phase         = ctx.base.firstTransformPhase
  def refchecksPhase(using Context): Phase              = ctx.base.refchecksPhase
  def elimRepeatedPhase(using Context): Phase           = ctx.base.elimRepeatedPhase
  def extensionMethodsPhase(using Context): Phase       = ctx.base.extensionMethodsPhase
  def explicitOuterPhase(using Context): Phase          = ctx.base.explicitOuterPhase
  def gettersPhase(using Context): Phase                = ctx.base.gettersPhase
  def erasurePhase(using Context): Phase                = ctx.base.erasurePhase
  def elimErasedValueTypePhase(using Context): Phase    = ctx.base.elimErasedValueTypePhase
  def lambdaLiftPhase(using Context): Phase             = ctx.base.lambdaLiftPhase
  def flattenPhase(using Context): Phase                = ctx.base.flattenPhase
  def genBCodePhase(using Context): Phase               = ctx.base.genBCodePhase

  def unfusedPhases(using Context): Array[Phase] = ctx.base.phases

  /** Replace all instances of `oldPhaseClass` in `current` phases
   *  by the result of `newPhases` applied to the old phase.
   */
  private def replace(oldPhaseClass: Class[? <: Phase], newPhases: Phase => List[Phase], current: List[List[Phase]]): List[List[Phase]] =
    current.map(_.flatMap(phase =>
      if (oldPhaseClass.isInstance(phase)) newPhases(phase) else phase :: Nil))
}
