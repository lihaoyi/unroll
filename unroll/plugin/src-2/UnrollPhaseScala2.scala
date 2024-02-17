package unroll

import scala.tools.nsc.symtab.Flags
import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.tools.nsc.{Global, Phase}
import tools.nsc.plugins.PluginComponent

class UnrollPhaseScala2(val global: Global) extends PluginComponent with TypingTransformers with Transform {

  import global._

  val runsAfter = List("typer")

  override val runsBefore = List("patmat")

  val phaseName = "unroll"

  override def newTransformer(unit: global.CompilationUnit): global.Transformer = {
    new UnrollTransformer(unit)
  }

  def findUnrollAnnotations(params: Seq[Symbol]): Seq[Int] = {
    params.toList.zipWithIndex.collect {
      case (v, i) if v.annotations.exists(_.tpe =:= typeOf[scala.annotation.unroll]) => i
    }
  }

  def copyValDef(vd: ValDef) = {
    val newMods = vd.mods.copy(flags = vd.mods.flags ^ Flags.DEFAULTPARAM)
    newStrictTreeCopier.ValDef(vd, newMods, vd.name, vd.tpt, EmptyTree)
      .setSymbol(vd.symbol)
  }

  def copySymbol(owner: Symbol, s: Symbol) = {
    val newSymbol = owner.newValueParameter(s.name.toTermName)
    newSymbol.setInfo(s.tpe)
    newSymbol
  }

  implicit class Setter[T <: Tree](t: T){
    def set(s: Symbol) = t.setType(s.tpe).setSymbol(s)
  }
  def generateSingleForwarder(implDef: ImplDef,
                              defdef: DefDef,
                              paramIndex: Int,
                              annotatedParamListIndex: Int,
                              paramLists: List[List[ValDef]]) = {
    val forwarderDefSymbol = defdef.symbol.owner.newMethod(defdef.name)
    val symbolReplacements = defdef
      .vparamss
      .flatten
      .map { p => p.symbol -> copySymbol(forwarderDefSymbol, p.symbol) }
      .toMap ++ {
      defdef.symbol.tpe match{
        case MethodType(originalParams, result) => Nil
        case PolyType(tparams, MethodType(originalParams, result)) =>
          // Not sure why this is necessary, but the `originalParams` here
          // is different from `defdef.vparamss`, and we need both
          originalParams.map(p => (p, copySymbol(forwarderDefSymbol, p)))
      }
    }

    val forwarderMethodType = defdef.symbol.tpe match{
      case MethodType(originalParams, result) =>
        val forwarderParams = originalParams.map(symbolReplacements)
        MethodType(forwarderParams.take(paramIndex), result)

      case PolyType(tparams, MethodType(originalParams, result)) =>
        val forwarderParams = originalParams.map(symbolReplacements)
        PolyType(tparams, MethodType(forwarderParams.take(paramIndex), result))
    }

    forwarderDefSymbol.setInfo(forwarderMethodType)

    val newParamLists = paramLists
      .zipWithIndex
      .map{ case (paramList, i) =>
        if (i != annotatedParamListIndex) paramList
        else paramList.take(paramIndex)
      }
      .map(_.map(copyValDef))

    val defaultCalls = Range(paramIndex, paramLists(annotatedParamListIndex).size).map{n =>
      val mangledName = defdef.name.toString + "$default$" + (n + 1)

      val defaultOwner =
        if (defdef.symbol.isConstructor) implDef.symbol.companionModule
        else implDef.symbol

      val defaultMember = defaultOwner.tpe.member(TermName(scala.reflect.NameTransformer.encode(mangledName)))
      Ident(mangledName).setSymbol(defaultMember).set(defaultMember)
    }

    val forwardedValueParams = newParamLists(annotatedParamListIndex).map(p => Ident(p.name).set(p.symbol))

    val forwarderThis = This(defdef.symbol.owner).set(defdef.symbol.owner)

    val forwarderInner =
      if (defdef.symbol.isConstructor) Super(forwarderThis, typeNames.EMPTY).set(defdef.symbol.owner)
      else forwarderThis

    val nestedForwarderMethodTypes = Seq
      .iterate(defdef.symbol.tpe, defdef.vparamss.length + 1){
        case MethodType(args, res) => res
        case PolyType(tparams, MethodType(args, res)) => res
      }
      .drop(1)

    val forwarderCallArgs = newParamLists.zipWithIndex.map{case (v, i) =>
      if (i == annotatedParamListIndex) forwardedValueParams ++ defaultCalls
      else v.map( p => Ident(p.name).set(p.symbol))
    }

    val forwarderCall0 = forwarderCallArgs
      .zip(nestedForwarderMethodTypes)
      .foldLeft(Select(forwarderInner, defdef.name).set(defdef.symbol): Tree){
        case (lhs, (ps, methodType)) => Apply(fun = lhs, args = ps).setType(methodType)
      }

    val forwarderCall =
      if (!defdef.symbol.isConstructor) forwarderCall0
      else Block(List(forwarderCall0), Literal(Constant(())).setType(typeOf[Unit]))

    val forwarderDef = treeCopy.DefDef(
      defdef,
      mods = defdef.mods,
      name = defdef.name,
      tparams = defdef.tparams,
      vparamss = newParamLists,
      tpt = defdef.tpt,
      rhs = forwarderCall
    ).set(forwarderDefSymbol)

    // No idea why this `if` guard is necessary, but without it we end
    if (defdef.symbol.owner.isTrait) {
      defdef.symbol.owner.info.asInstanceOf[ClassInfoType].decls.enter(forwarderDefSymbol)
    }

    val (fromSyms, toSyms) = symbolReplacements.toList.unzip
    forwarderDef.substituteSymbols(fromSyms, toSyms).asInstanceOf[DefDef]
  }



  class UnrollTransformer(unit: global.CompilationUnit) extends TypingTransformer(unit) {
    def generateDefForwarders(implDef: ImplDef): List[List[DefDef]] = {
      implDef.impl.body.collect{ case defdef: DefDef =>

        val annotatedOpt =
          if (defdef.symbol.isCaseCopy && defdef.symbol.name.toString == "copy") {
            Some(defdef.symbol.owner.primaryConstructor)
          } else if (defdef.symbol.isCaseApplyOrUnapply && defdef.symbol.name.toString == "apply"){
            val classConstructor = defdef.symbol.owner.companionClass.primaryConstructor
            if (classConstructor == NoSymbol) None
            else Some(classConstructor)
          } else {
            Some(defdef.symbol)
          }

        // Somehow the "apply" methods of case class companions defined within local scopes
        // do not have companion class primary constructor symbols, so we just skip them here
        annotatedOpt.toList.flatMap{ annotated =>
          try {
            annotated.tpe.paramss
              .zipWithIndex
              .flatMap{case (annotatedParamList, paramListIndex) =>
                val annotationIndices = findUnrollAnnotations(annotatedParamList)
                if (annotationIndices.isEmpty) None
                else Some((annotatedParamList, annotationIndices, paramListIndex))
              } match{
              case Nil => Nil
              case Seq((annotatedParamList, annotationIndices, paramListIndex)) =>
                for (paramIndex <- annotationIndices) yield {
                  generateSingleForwarder(
                    implDef,
                    defdef,
                    paramIndex,
                    paramListIndex,
                    defdef.vparamss
                  )
                }

              case multiple => sys.error("Multiple")
            }
          }catch{case e: Throwable =>
            throw new Exception(
              s"Failed to generate unrolled defs for $defdef in ${implDef.symbol} in ${implDef.symbol.pos}",
              e
            )
          }
        }
      }
    }

    override def transform(tree: global.Tree): global.Tree = {
      tree match{
        case md: ModuleDef =>
          val allNewMethods = generateDefForwarders(md).flatten

          val classInfoType = md.symbol.moduleClass.info.asInstanceOf[ClassInfoType]
          val newClassInfoType = classInfoType.copy(decls = newScopeWith(allNewMethods.map(_.symbol) ++ classInfoType.decls:_*))

          md.symbol.moduleClass.setInfo(newClassInfoType)
          super.transform(
            treeCopy.ModuleDef(
              md,
              mods = md.mods,
              name = md.name,
              impl = treeCopy.Template(
                md.impl,
                parents = md.impl.parents,
                self = md.impl.self,
                body = md.impl.body ++ allNewMethods
              )
            )
          )
        case cd: ClassDef =>
          val allNewMethods = generateDefForwarders(cd).flatten
          super.transform(
            treeCopy.ClassDef(
              cd,
              mods = cd.mods,
              name = cd.name,
              tparams = cd.tparams,
              impl = treeCopy.Template(
                cd.impl,
                parents = cd.impl.parents,
                self = cd.impl.self,
                body = cd.impl.body ++ allNewMethods
              )
            )
          )
        case _ => super.transform(tree)
      }
    }
  }
}

