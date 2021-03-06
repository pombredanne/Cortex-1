package services

import java.util.Date

import javax.inject.{ Inject, Named }

import scala.annotation.implicitNotFound
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import scala.concurrent.duration.Duration
import scala.concurrent.duration.Duration.Infinite
import scala.util.Random

import akka.actor.{ Actor, ActorRef, ActorSystem, actorRef2Scala }
import akka.pattern.ask
import akka.util.Timeout

import play.api.{ Configuration, Logger }
import play.api.libs.json.{ JsString, JsValue }

import models.{ Analyzer, Artifact, Job, JobStatus }

class JobSrv @Inject() (
    analyzerSrv: AnalyzerSrv,
    @Named("JobActor") jobActor: ActorRef,
    implicit val ec: ExecutionContext,
    implicit val system: ActorSystem) {
  import JobActor._
  implicit val timeout = Timeout(5.seconds)

  def list(dataTypeFilter: Option[String], dataFilter: Option[String], analyzerFilter: Option[String], start: Int, limit: Int): Future[(Int, Seq[Job])] = {
    (jobActor ? ListJobs(dataTypeFilter, dataFilter, analyzerFilter, start, limit)).map {
      case JobList(total, jobs) ⇒ total → jobs
      case _                    ⇒ sys.error("TODO")
    }
  }

  def get(jobId: String): Future[Job] = (jobActor ? GetJob(jobId)).map {
    case j: Job      ⇒ j
    case JobNotFound ⇒ sys.error("job not found")
    case _           ⇒ sys.error("TODO")
  }

  def create(artifact: Artifact, analyzerId: String): Future[Job] = {
    analyzerSrv.get(analyzerId)
      .map { analyzer ⇒ create(artifact, analyzer) }
      .getOrElse(Future.failed(new Exception("analyzer not found")))
  }

  def create(artifact: Artifact, analyzer: Analyzer): Future[Job] =
    (jobActor ? CreateJob(artifact, analyzer)) map {
      case j: Job ⇒ j
      case _      ⇒ sys.error("TODO")
    }

  def remove(jobId: String): Future[Unit] = {
    (jobActor ? RemoveJob(jobId)).map {
      case JobRemoved  ⇒ ()
      case JobNotFound ⇒ sys.error("job not found")
      case _           ⇒ sys.error("TODO")
    }
  }

  def waitReport(jobId: String, atMost: Duration): Future[(JobStatus.Type, JsValue)] = {
    val statusResult = get(jobId)
      .flatMap(_.report)
      .map((JobStatus.Success, _))
      .recover { case error ⇒ (JobStatus.Failure, JsString(error.getMessage)) }

    atMost match {
      case _: Infinite ⇒ statusResult
      case duration: FiniteDuration ⇒
        val prom = Promise[(JobStatus.Type, JsValue)]()
        val timeout = system.scheduler.scheduleOnce(duration) { prom.success((JobStatus.Failure, JsString("Timeout"))); () }
        statusResult onComplete { case _ ⇒ timeout.cancel() }
        Future.firstCompletedOf(List(statusResult, prom.future))
    }
  }
}

object JobActor {
  case class ListJobs(dataTypeFilter: Option[String], dataFilter: Option[String], analyzerFilter: Option[String], start: Int, limit: Int)
  case class JobList(total: Int, jobs: Seq[Job])
  case class GetJob(jobId: String)
  case object JobNotFound
  case class CreateJob(artifact: Artifact, analyzer: Analyzer)
  case class RemoveJob(jobId: String)
  case object JobRemoved
  case object JobCleanup
}

class JobActor(
    jobLifeTime: Duration,
    jobCleanupPeriod: Duration,
    analyzerSrv: AnalyzerSrv,
    implicit val ec: ExecutionContext) extends Actor {

  import JobActor._
  @Inject def this(
    configuration: Configuration,
    analyzerSrv: AnalyzerSrv,
    ec: ExecutionContext) =
    this(
      configuration.getString("job.lifetime").fold[Duration](Duration.Inf)(d ⇒ Duration(d)),
      configuration.getString("job.cleanupPeriod").fold[Duration](Duration.Inf)(d ⇒ Duration(d)),
      analyzerSrv,
      ec)

  lazy val logger = Logger(getClass)

  (jobLifeTime, jobCleanupPeriod) match {
    case (_: FiniteDuration, jcp: FiniteDuration) ⇒ context.system.scheduler.schedule(jcp, jcp, self, JobCleanup)
    case (_: Infinite, _: Infinite)               ⇒ // no cleanup
    case (_: FiniteDuration, _: Infinite)         ⇒ logger.warn("Job lifetime is configured but cleanup period is not set. Job will never be removed")
    case (_: Infinite, _: FiniteDuration)         ⇒ logger.warn("Job cleanup period is configured but job lifetime is not set. Job will never be removed")
  }

  private[services] def removeJob(jobs: List[Job], jobId: String): Option[List[Job]] =
    jobs.headOption match {
      case Some(j) if j.id == jobId ⇒ Some(jobs.tail)
      case Some(j)                  ⇒ removeJob(jobs.tail, jobId).map(j :: _)
      case None                     ⇒ None
    }

  def jobState(jobs: List[Job]): Receive = {
    case ListJobs(dataTypeFilter, dataFilter, analyzerFilter, start, limit) ⇒
      val filteredJobs = jobs.filter(j ⇒
        dataTypeFilter.fold(true)(j.artifact.dataTypeFilter) &&
          dataFilter.fold(true)(j.artifact.dataFilter) &&
          analyzerFilter.fold(true)(j.analyzerId.contains))
      sender ! JobList(filteredJobs.size, filteredJobs.drop(start).take(limit))
    case GetJob(jobId) ⇒ sender ! jobs.find(_.id == jobId).getOrElse(JobNotFound)
    case RemoveJob(jobId) ⇒
      removeJob(jobs, jobId) match {
        case Some(j) ⇒
          sender ! JobRemoved
          context.become(jobState(j))
        case None ⇒ sender ! JobNotFound
      }
    case CreateJob(artifact, analyzer) ⇒
      val jobId = Random.alphanumeric.take(16).mkString
      val job = Job(jobId, analyzer.id, artifact, analyzer.analyze(artifact))
      sender ! job
      context.become(jobState(job :: jobs))
    case JobCleanup if jobLifeTime.isInstanceOf[FiniteDuration] ⇒
      val now = (new Date).getTime
      val limitDate = new Date(now - jobLifeTime.toMillis)
      context.become(jobState(jobs.takeWhile(_.date after limitDate)))
  }

  override def receive = jobState(Nil)
}