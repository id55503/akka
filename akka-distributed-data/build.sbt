import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, Unidoc, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettingsNoVerificationOfDiagrams

Unidoc.javadocSettings

OSGi.distributedData

MultiNode.multiJvmSettings

libraryDependencies ++= Dependencies.distributedData

//MimaKeys.previousArtifact := akkaPreviousArtifact("akka-distributed-data").value
