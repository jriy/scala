/* NSC -- new Scala compiler
 * Copyright 2005-2015 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package backend.jvm
package opt

import scala.annotation.switch
import scala.collection.immutable
import scala.collection.immutable.IntMap
import scala.reflect.internal.util.NoPosition
import scala.tools.asm.{Handle, Type, Opcodes}
import scala.tools.asm.tree._
import scala.tools.nsc.backend.jvm.BTypes.InternalName
import BytecodeUtils._
import BackendReporting._
import Opcodes._
import scala.tools.nsc.backend.jvm.opt.ByteCodeRepository.CompilationUnit
import scala.collection.convert.decorateAsScala._

class ClosureOptimizer[BT <: BTypes](val btypes: BT) {
  import btypes._
  import callGraph._
  import coreBTypes._
  import backendUtils._
  import ClosureOptimizer._

  /**
   * If a closure is allocated and invoked within the same method, re-write the invocation to the
   * closure body method.
   *
   * Note that the closure body method (generated by delambdafy:method) takes additional parameters
   * for the values captured by the closure. The bytecode is transformed from
   *
   *   [generate captured values]
   *   [closure init, capturing values]
   *   [...]
   *   [load closure object]
   *   [generate closure invocation arguments]
   *   [invoke closure.apply]
   *
   * to
   *
   *   [generate captured values]
   *   [store captured values into new locals]
   *   [load the captured values from locals]    // a future optimization will eliminate the closure
   *   [closure init, capturing values]          // instantiation if the closure object becomes unused
   *   [...]
   *   [load closure object]
   *   [generate closure invocation arguments]
   *   [store argument values into new locals]
   *   [drop the closure object]
   *   [load captured values from locals]
   *   [load argument values from locals]
   *   [invoke the closure body method]
   */
  def rewriteClosureApplyInvocations(): Unit = {
    implicit object closureInitOrdering extends Ordering[ClosureInstantiation] {
      override def compare(x: ClosureInstantiation, y: ClosureInstantiation): Int = {
        val cls = x.ownerClass.internalName compareTo y.ownerClass.internalName
        if (cls != 0) return cls

        val mName = x.ownerMethod.name compareTo y.ownerMethod.name
        if (mName != 0) return mName

        val mDesc = x.ownerMethod.desc compareTo y.ownerMethod.desc
        if (mDesc != 0) return mDesc

        def pos(inst: ClosureInstantiation) = inst.ownerMethod.instructions.indexOf(inst.lambdaMetaFactoryCall.indy)
        pos(x) - pos(y)
      }
    }

    // For each closure instantiation, a list of callsites of the closure that can be re-written
    // If a callsite cannot be rewritten, for example because the lambda body method is not accessible,
    // a warning is returned instead.
    val callsitesToRewrite: List[(ClosureInstantiation, List[Either[RewriteClosureApplyToClosureBodyFailed, (MethodInsnNode, Int)]])] = {
      closureInstantiations.iterator.flatMap({
        case (methodNode, closureInits) =>
          if (!AsmAnalyzer.sizeOKForSourceValue(methodNode)) Nil else {
            // A lazy val to ensure the analysis only runs if necessary (the value is passed by name to `closureCallsites`)
            // We don't need to worry about the method being too large for running an analysis: large
            // methods are not added to the call graph / closureInstantiations map.
            lazy val prodCons = new ProdConsAnalyzer(methodNode, closureInits.valuesIterator.next().ownerClass.internalName)
            // sorting for bytecode stability (e.g. indices of local vars created during the rewrite)
            val sortedInits = immutable.TreeSet.empty ++ closureInits.values
            sortedInits.iterator.map(init => (init, closureCallsites(init, prodCons))).filter(_._2.nonEmpty)
          }
      }).toList // mapping to a list (not a map) to keep the sorting
    }

    // Rewrite all closure callsites (or issue inliner warnings for those that cannot be rewritten)
    for ((closureInit, callsites) <- callsitesToRewrite) {
      // Local variables that hold the captured values and the closure invocation arguments.
      // They are lazy vals to ensure that locals for captured values are only allocated if there's
      // actually a callsite to rewrite (an not only warnings to be issued).
      lazy val (localsForCapturedValues, argumentLocalsList) = localsForClosureRewrite(closureInit)
      for (callsite <- callsites) callsite match {
        case Left(warning) =>
          backendReporting.inlinerWarning(warning.pos, warning.toString)

        case Right((invocation, stackHeight)) =>
          rewriteClosureApplyInvocation(closureInit, invocation, stackHeight, localsForCapturedValues, argumentLocalsList)
      }
    }
  }

  /**
   * Insert instructions to store the values captured by a closure instantiation into local variables,
   * and load the values back to the stack.
   *
   * Returns the list of locals holding those captured values, and a list of locals that should be
   * used at the closure invocation callsite to store the arguments passed to the closure invocation.
   */
  private def localsForClosureRewrite(closureInit: ClosureInstantiation): (LocalsList, LocalsList) = {
    val ownerMethod = closureInit.ownerMethod
    val captureLocals = storeCaptures(closureInit)

    // allocate locals for storing the arguments of the closure apply callsites.
    // if there are multiple callsites, the same locals are re-used.
    val argTypes = closureInit.lambdaMetaFactoryCall.samMethodType.getArgumentTypes
    val firstArgLocal = ownerMethod.maxLocals

    val argLocals = LocalsList.fromTypes(firstArgLocal, argTypes)
    ownerMethod.maxLocals = firstArgLocal + argLocals.size

    (captureLocals, argLocals)
  }

  /**
   * Find all callsites of a closure within the method where the closure is allocated.
   */
  private def closureCallsites(closureInit: ClosureInstantiation, prodCons: => ProdConsAnalyzer): List[Either[RewriteClosureApplyToClosureBodyFailed, (MethodInsnNode, Int)]] = {
    val ownerMethod = closureInit.ownerMethod
    val ownerClass = closureInit.ownerClass
    val lambdaBodyHandle = closureInit.lambdaMetaFactoryCall.implMethod

    ownerMethod.instructions.iterator.asScala.collect({
      case invocation: MethodInsnNode if isSamInvocation(invocation, closureInit, prodCons) =>
        // TODO: This is maybe over-cautious.
        // We are checking if the closure body method is accessible at the closure callsite.
        // If the closure allocation has access to the body method, then the callsite (in the same
        // method as the alloction) should have access too.
        val bodyAccessible: Either[OptimizerWarning, Boolean] = for {
          (bodyMethodNode, declClass) <- byteCodeRepository.methodNode(lambdaBodyHandle.getOwner, lambdaBodyHandle.getName, lambdaBodyHandle.getDesc): Either[OptimizerWarning, (MethodNode, InternalName)]
          isAccessible                <- inliner.memberIsAccessible(bodyMethodNode.access, classBTypeFromParsedClassfile(declClass), classBTypeFromParsedClassfile(lambdaBodyHandle.getOwner), ownerClass)
        } yield {
          isAccessible
        }

        def pos = callGraph.callsites(ownerMethod).get(invocation).map(_.callsitePosition).getOrElse(NoPosition)
        val stackSize: Either[RewriteClosureApplyToClosureBodyFailed, Int] = bodyAccessible match {
          case Left(w)      => Left(RewriteClosureAccessCheckFailed(pos, w))
          case Right(false) => Left(RewriteClosureIllegalAccess(pos, ownerClass.internalName))
          case _            => Right(prodCons.frameAt(invocation).getStackSize)
        }

        stackSize.right.map((invocation, _))
    }).toList
  }

  /**
   * Check whether `invocation` invokes the SAM of the IndyLambda `closureInit`.
   *
   * In addition to a perfect match, we also identify cases where a generic FunctionN is created
   * but the invocation is to a specialized variant apply$sp... Vice-versa, we also allow the
   * case where a specialized FunctionN$sp.. is created but the generic apply is invoked. In
   * these cases, the translation will introduce the necessary box / unbox invocations. Example:
   *
   *   val f: Int => Any = (x: Int) => 1
   *   f(10)
   *
   * The IndyLambda creates a specialized `JFunction1$mcII$sp`, whose SAM is `apply$mcII$sp(I)I`.
   * The invocation calls `apply(Object)Object`: the method name and type don't match.
   * We identify these cases, insert the necessary unbox operation for the arguments, and invoke
   * the `$anonfun(I)I` method.
   *
   * Tests in InlinerTest.optimizeSpecializedClosures. In that test, methods t4/t4a/t5/t8 show
   * examples where the parameters have to be unboxed because generic `apply` is called, but the
   * lambda body method takes primitive types.
   * The opposite case is in t9: a the specialized `apply$sp..` is invoked, but the lambda body
   * method takes boxed arguments, so we have to insert boxing operations.
   */
  private def isSamInvocation(invocation: MethodInsnNode, closureInit: ClosureInstantiation, prodCons: => ProdConsAnalyzer): Boolean = {
    val indy = closureInit.lambdaMetaFactoryCall.indy
    if (invocation.getOpcode == INVOKESTATIC) false
    else {
      def closureIsReceiver = {
        val invocationFrame = prodCons.frameAt(invocation)
        val receiverSlot = {
          val numArgs = Type.getArgumentTypes(invocation.desc).length
          invocationFrame.stackTop - numArgs
        }
        val receiverProducers = prodCons.initialProducersForValueAt(invocation, receiverSlot)
        receiverProducers.size == 1 && receiverProducers.head == indy
      }

      def isSpecializedVersion(specName: String, nonSpecName: String) = specName.startsWith(nonSpecName) && specName.substring(nonSpecName.length).matches(specializationSuffix)

      def sameOrSpecializedType(specTp: Type, nonSpecTp: Type) = {
        specTp == nonSpecTp || {
          val specDesc = specTp.getDescriptor
          val nonSpecDesc = nonSpecTp.getDescriptor
          specDesc.length == 1 && primitives.contains(specDesc) && nonSpecDesc == ObjectRef.descriptor
        }
      }

      def specializedDescMatches(specMethodDesc: String, nonSpecMethodDesc: String) = {
        val specArgs = Type.getArgumentTypes(specMethodDesc)
        val nonSpecArgs = Type.getArgumentTypes(nonSpecMethodDesc)
        specArgs.corresponds(nonSpecArgs)(sameOrSpecializedType) && sameOrSpecializedType(Type.getReturnType(specMethodDesc), Type.getReturnType(nonSpecMethodDesc))
      }

      def nameAndDescMatch = {
        val aName = invocation.name
        val bName = indy.name
        val aDesc = invocation.desc
        val bDesc = closureInit.lambdaMetaFactoryCall.samMethodType.getDescriptor
        if (aName == bName) aDesc == bDesc
        else if (isSpecializedVersion(aName, bName)) specializedDescMatches(aDesc, bDesc)
        else if (isSpecializedVersion(bName, aName)) specializedDescMatches(bDesc, aDesc)
        else false
      }

      nameAndDescMatch && closureIsReceiver // most expensive check last
    }
  }

  private def isPrimitiveType(asmType: Type) = {
    val sort = asmType.getSort
    Type.VOID <= sort && sort <= Type.DOUBLE
  }

  private def unboxOp(primitiveType: Type): MethodInsnNode = {
    val bType = bTypeForDescriptorOrInternalNameFromClassfile(primitiveType.getDescriptor)
    val MethodNameAndType(name, methodBType) = asmUnboxTo(bType)
    new MethodInsnNode(INVOKESTATIC, srBoxesRunTimeRef.internalName, name, methodBType.descriptor, /*itf =*/ false)
  }

  private def boxOp(primitiveType: Type): MethodInsnNode = {
    val bType = bTypeForDescriptorOrInternalNameFromClassfile(primitiveType.getDescriptor)
    val MethodNameAndType(name, methodBType) = asmBoxTo(bType)
    new MethodInsnNode(INVOKESTATIC, srBoxesRunTimeRef.internalName, name, methodBType.descriptor, /*itf =*/ false)
  }

  /**
   * The argument types of the lambda body method may differ in two ways from the argument types of
   * the closure member method that is invoked (and replaced by a call to the body).
   *   - The lambda body method may have more specific types than the invoked closure member, see
   *     comment in [[LambdaMetaFactoryCall.unapply]].
   *   - The invoked closure member might be a specialized variant of the SAM or vice-versa, see
   *     comment method [[isSamInvocation]].
   */
  private def adaptStoredArguments(closureInit: ClosureInstantiation, invocation: MethodInsnNode): Int => Option[AbstractInsnNode] = {
    val invokeDesc = invocation.desc
    // The lambda body method has additional parameters for captured values. Here we need to consider
    // only those parameters of the body method that correspond to lambda parameters. This happens
    // to be exactly LMF.instantiatedMethodType. In fact, `LambdaMetaFactoryCall.unapply` ensures
    // that the body method signature is exactly (capturedParams + instantiatedMethodType).
    val lambdaBodyMethodDescWithoutCaptures = closureInit.lambdaMetaFactoryCall.instantiatedMethodType.getDescriptor
    if (invokeDesc == lambdaBodyMethodDescWithoutCaptures) {
      _ => None
    } else {
      val invokeArgTypes = Type.getArgumentTypes(invokeDesc)
      val implMethodArgTypes = Type.getArgumentTypes(lambdaBodyMethodDescWithoutCaptures)
      val res = new Array[Option[AbstractInsnNode]](invokeArgTypes.length)
      for (i <- invokeArgTypes.indices) {
        if (invokeArgTypes(i) == implMethodArgTypes(i)) {
          res(i) = None
        } else if (isPrimitiveType(implMethodArgTypes(i)) && invokeArgTypes(i).getDescriptor == ObjectRef.descriptor) {
          res(i) = Some(unboxOp(implMethodArgTypes(i)))
        } else if (isPrimitiveType(invokeArgTypes(i)) && implMethodArgTypes(i).getDescriptor == ObjectRef.descriptor) {
          res(i) = Some(boxOp(invokeArgTypes(i)))
        } else {
          assert(!isPrimitiveType(invokeArgTypes(i)), invokeArgTypes(i))
          assert(!isPrimitiveType(implMethodArgTypes(i)), implMethodArgTypes(i))
          // The comment in the unapply method of `LambdaMetaFactoryCall` explains why we have to introduce
          // casts for arguments that have different types in samMethodType and instantiatedMethodType.
          //
          // Note:
          //   - invokeArgTypes is the same as the argument types in the IndyLambda's samMethodType,
          //     this is ensured by the `isSamInvocation` filter in this file
          //   - implMethodArgTypes is the same as the arg types in the IndyLambda's instantiatedMethodType,
          //     this is ensured by the unapply method in LambdaMetaFactoryCall (file CallGraph)
          res(i) = Some(new TypeInsnNode(CHECKCAST, implMethodArgTypes(i).getInternalName))
        }
      }
      res
    }
  }

  private def rewriteClosureApplyInvocation(closureInit: ClosureInstantiation, invocation: MethodInsnNode, stackHeight: Int, localsForCapturedValues: LocalsList, argumentLocalsList: LocalsList): Unit = {
    val ownerMethod = closureInit.ownerMethod
    val lambdaBodyHandle = closureInit.lambdaMetaFactoryCall.implMethod

    // store arguments
    insertStoreOps(invocation, ownerMethod, argumentLocalsList, adaptStoredArguments(closureInit, invocation))

    // drop the closure from the stack
    ownerMethod.instructions.insertBefore(invocation, new InsnNode(POP))

    // load captured values and arguments
    insertLoadOps(invocation, ownerMethod, localsForCapturedValues)
    insertLoadOps(invocation, ownerMethod, argumentLocalsList)

    // update maxStack
    // One slot per value is correct for long / double, see comment in the `analysis` package object.
    val numCapturedValues = localsForCapturedValues.locals.length
    val invocationStackHeight = stackHeight + numCapturedValues - 1 // -1 because the closure is gone
    if (invocationStackHeight > ownerMethod.maxStack)
      ownerMethod.maxStack = invocationStackHeight

    // replace the callsite with a new call to the body method
    val bodyOpcode = (lambdaBodyHandle.getTag: @switch) match {
      case H_INVOKEVIRTUAL    => INVOKEVIRTUAL
      case H_INVOKESTATIC     => INVOKESTATIC
      case H_INVOKESPECIAL    => INVOKESPECIAL
      case H_INVOKEINTERFACE  => INVOKEINTERFACE
      case H_NEWINVOKESPECIAL =>
        val insns = ownerMethod.instructions
        insns.insertBefore(invocation, new TypeInsnNode(NEW, lambdaBodyHandle.getOwner))
        insns.insertBefore(invocation, new InsnNode(DUP))
        INVOKESPECIAL
    }
    val isInterface = bodyOpcode == INVOKEINTERFACE
    val bodyInvocation = new MethodInsnNode(bodyOpcode, lambdaBodyHandle.getOwner, lambdaBodyHandle.getName, lambdaBodyHandle.getDesc, isInterface)
    ownerMethod.instructions.insertBefore(invocation, bodyInvocation)

    val bodyReturnType = Type.getReturnType(lambdaBodyHandle.getDesc)
    val invocationReturnType = Type.getReturnType(invocation.desc)
    if (isPrimitiveType(invocationReturnType) && bodyReturnType.getDescriptor == ObjectRef.descriptor) {
      val op =
        if (invocationReturnType.getSort == Type.VOID) getPop(1)
        else unboxOp(invocationReturnType)
      ownerMethod.instructions.insertBefore(invocation, op)
    } else if (isPrimitiveType(bodyReturnType) && invocationReturnType.getDescriptor == ObjectRef.descriptor) {
      val op =
        if (bodyReturnType.getSort == Type.VOID) getBoxedUnit
        else boxOp(bodyReturnType)
      ownerMethod.instructions.insertBefore(invocation, op)
    } else {
      // see comment of that method
      fixLoadedNothingOrNullValue(bodyReturnType, bodyInvocation, ownerMethod, btypes)
    }

    ownerMethod.instructions.remove(invocation)

    // update the call graph
    val originalCallsite = callGraph.removeCallsite(invocation, ownerMethod)

    // the method node is needed for building the call graph entry
    val bodyMethod = byteCodeRepository.methodNode(lambdaBodyHandle.getOwner, lambdaBodyHandle.getName, lambdaBodyHandle.getDesc)
    def bodyMethodIsBeingCompiled = byteCodeRepository.classNodeAndSource(lambdaBodyHandle.getOwner).map(_._2 == CompilationUnit).getOrElse(false)
    val callee = bodyMethod.map({
      case (bodyMethodNode, bodyMethodDeclClass) =>
        val bodyDeclClassType = classBTypeFromParsedClassfile(bodyMethodDeclClass)
        Callee(
          callee = bodyMethodNode,
          calleeDeclarationClass = bodyDeclClassType,
          safeToInline = compilerSettings.YoptInlineGlobal || bodyMethodIsBeingCompiled,
          safeToRewrite = false, // the lambda body method is not a trait interface method
          annotatedInline = false,
          annotatedNoInline = false,
          samParamTypes = callGraph.samParamTypes(bodyMethodNode, bodyDeclClassType),
          calleeInfoWarning = None)
    })
    val argInfos = closureInit.capturedArgInfos ++ originalCallsite.map(cs => cs.argInfos map {
      case (index, info) => (index + numCapturedValues, info)
    }).getOrElse(IntMap.empty)
    val bodyMethodCallsite = Callsite(
      callsiteInstruction = bodyInvocation,
      callsiteMethod = ownerMethod,
      callsiteClass = closureInit.ownerClass,
      callee = callee,
      argInfos = argInfos,
      callsiteStackHeight = invocationStackHeight,
      receiverKnownNotNull = true, // see below (*)
      callsitePosition = originalCallsite.map(_.callsitePosition).getOrElse(NoPosition),
      annotatedInline = false,
      annotatedNoInline = false
    )
    // (*) The documentation in class LambdaMetafactory says:
    //     "if implMethod corresponds to an instance method, the first capture argument
    //     (corresponding to the receiver) must be non-null"
    // Explanation: If the lambda body method is non-static, the receiver is a captured
    // value. It can only be captured within some instance method, so we know it's non-null.
    callGraph.addCallsite(bodyMethodCallsite)

    // Rewriting a closure invocation may render code unreachable. For example, the body method of
    // (x: T) => ??? has return type Nothing$, and an ATHROW is added (see fixLoadedNothingOrNullValue).
    unreachableCodeEliminated -= ownerMethod

    if (hasAdaptedImplMethod(closureInit) && inliner.canInlineBody(bodyMethodCallsite).isEmpty)
      inliner.inlineCallsite(bodyMethodCallsite)
  }

  /**
   * Stores the values captured by a closure creation into fresh local variables, and loads the
   * values back onto the stack. Returns the list of locals holding the captured values.
   */
  private def storeCaptures(closureInit: ClosureInstantiation): LocalsList = {
    val indy = closureInit.lambdaMetaFactoryCall.indy
    val capturedTypes = Type.getArgumentTypes(indy.desc)
    val firstCaptureLocal = closureInit.ownerMethod.maxLocals

    // This could be optimized: in many cases the captured values are produced by LOAD instructions.
    // If the variable is not modified within the method, we could avoid introducing yet another
    // local. On the other hand, further optimizations (copy propagation, remove unused locals) will
    // clean it up.

    val localsForCaptures = LocalsList.fromTypes(firstCaptureLocal, capturedTypes)
    closureInit.ownerMethod.maxLocals = firstCaptureLocal + localsForCaptures.size

    insertStoreOps(indy, closureInit.ownerMethod, localsForCaptures, _ => None)
    insertLoadOps(indy, closureInit.ownerMethod, localsForCaptures)

    localsForCaptures
  }

  /**
   * Insert store operations in front of the `before` instruction to copy stack values into the
   * locals denoted by `localsList`.
   *
   * The lowest stack value is stored in the head of the locals list, so the last local is stored first.
   */
  private def insertStoreOps(before: AbstractInsnNode, methodNode: MethodNode, localsList: LocalsList, beforeStore: Int => Option[AbstractInsnNode]) = {
    // The first instruction needs to store into the last local of the `localsList`.
    // To avoid reversing the list, we use `insert(previous)`.
    val previous = before.getPrevious
    def ins(op: AbstractInsnNode) = methodNode.instructions.insert(previous, op)
    for ((l, i) <- localsList.locals.zipWithIndex) {
      ins(new VarInsnNode(l.storeOpcode, l.local))
      beforeStore(i) foreach ins
    }
  }

  /**
   * Insert load operations in front of the `before` instruction to copy the local values denoted
   * by `localsList` onto the stack.
   *
   * The head of the locals list will be the lowest value on the stack, so the first local is loaded first.
   */
  private def insertLoadOps(before: AbstractInsnNode, methodNode: MethodNode, localsList: LocalsList) = {
    for (l <- localsList.locals) {
      val op = new VarInsnNode(l.loadOpcode, l.local)
      methodNode.instructions.insertBefore(before, op)
    }
  }

  /**
   * A list of local variables. Each local stores information about its type, see class [[Local]].
   */
  case class LocalsList(locals: List[Local]) {
    val size = locals.iterator.map(_.size).sum
  }

  object LocalsList {
    /**
     * A list of local variables starting at `firstLocal` that can hold values of the types in the
     * `types` parameter.
     *
     * For example, `fromTypes(3, Array(Int, Long, String))` returns
     *   Local(3, intOpOffset)  ::
     *   Local(4, longOpOffset) ::  // note that this local occupies two slots, the next is at 6
     *   Local(6, refOpOffset)  ::
     *   Nil
     */
    def fromTypes(firstLocal: Int, types: Array[Type]): LocalsList = {
      var sizeTwoOffset = 0
      val locals: List[Local] = types.indices.map(i => {
        // The ASM method `type.getOpcode` returns the opcode for operating on a value of `type`.
        val offset = types(i).getOpcode(ILOAD) - ILOAD
        val local = Local(firstLocal + i + sizeTwoOffset, offset)
        if (local.size == 2) sizeTwoOffset += 1
        local
      })(collection.breakOut)
      LocalsList(locals)
    }
  }

  /**
   * Stores a local variable index the opcode offset required for operating on that variable.
   *
   * The xLOAD / xSTORE opcodes are in the following sequence: I, L, F, D, A, so the offset for
   * a local variable holding a reference (`A`) is 4. See also method `getOpcode` in [[scala.tools.asm.Type]].
   */
  case class Local(local: Int, opcodeOffset: Int) {
    def size = if (loadOpcode == LLOAD || loadOpcode == DLOAD) 2  else 1

    def loadOpcode = ILOAD + opcodeOffset
    def storeOpcode = ISTORE + opcodeOffset
  }
}

object ClosureOptimizer {
  val primitives = "BSIJCFDZV"
  val specializationSuffix = s"(\\$$mc[$primitives]+\\$$sp)"
}
