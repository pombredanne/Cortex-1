import play.api.{ Configuration, Environment, Mode }
import play.api.libs.concurrent.AkkaGuiceSupport

import com.google.inject.AbstractModule

import net.codingwell.scalaguice.ScalaModule

import controllers.{ AssetCtrl, AssetCtrlDev, AssetCtrlProd }
import services.JobActor

class Module(environment: Environment, configuration: Configuration) extends AbstractModule with ScalaModule with AkkaGuiceSupport {

  override def configure() = {
    bindActor[JobActor]("JobActor")

    if (environment.mode == Mode.Prod)
      bind[AssetCtrl].to[AssetCtrlProd]
    else
      bind[AssetCtrl].to[AssetCtrlDev]
  }
}
