package link.naicode.dhmacauth

import link.naicode.utils.math.HexString

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{Success, Try}

import spray.http._
import spray.http.HttpHeaders.`WWW-Authenticate`
import spray.routing.RequestContext
import spray.routing.authentication.HttpAuthenticator

/**
 * Created by naicode on 9/10/14.
 */

/**
 * The AuthContextCreator is responsible to convert the given
 * userId and key security level to a more useful information
 * like e.g. a User instance or similar. The returned value
 * will be available inside the `authenticate(methode) {...}` scope
 *
 * Note that the implementation of this class should be thread save
 */
abstract class AuthContextCreator[T] extends Function2[Int,Byte,T] {
  def apply(userId: Int, securityLevel: Byte):T
}


/**
 * This class supplies HMAC authentication in combination with
 * the `authenticate`-Directive. A automatic conversation form
 * a bar userID+keySecurityLevel to a custom type can be provided
 * by the authContextCreator. A Resource to Store/Retrive/Update
 * keys is passed to this class via the authKeyStore implementation.
 * 
 * Additionally the validity of kay in context of a `DHmacAuthenticator`
 * is only given if the keys securityLevel is equals or higher then
 * the level of the `DHmacAuthenticator` instance. Use `withSecurityLevel`
 * to get multiple similar Authenticators with different Security levels
 * 
 * By default this Authenticator calls `refreshTimeoutForKey` from [[link.naicode.dhmacauth.AuthKeyStore]]
 * after each valid usage of a given key. This behaviour can be disabled via the `refreshKeysOnValid`
 * parameter.
 *
 * Note that the security level is passed as additional parameter for
 * the `WWW-Authenticate` challenge header to provide integrity with
 * one realm having methods with multiple different securityLevels
 *
 * @param authKeyStore
 * @param authContextCreator
 * @param realm
 * @param securityLevel
 * @param refreshKeysOnValid
 * @param executionContext
 * @tparam U
 */
class DHmacAuthenticator[U](val authKeyStore: AuthKeyStore,
                            val authContextCreator: AuthContextCreator[U],
                            val realm: String, 
                            val securityLevel: Byte = 0,
                            val refreshKeysOnValid:Boolean = true
                             )(implicit val executionContext:ExecutionContext)
  extends HttpAuthenticator[U] {


  /**
   * This methode is called by `HttpAuthenticate` if
   * Future.success(None) is returned `HttpAuthenticate` assumes
   * that the authentication has failed and responds accordingly.
   * @param credentials generated by spray-routes
   * @param ctx other request parameter
   * @return some logindata of type T or none if invalide
   */
  override def authenticate(credentials: Option[HttpCredentials], ctx: RequestContext): Future[Option[U]] = {
    Future.successful(extractInfo(credentials) match {
      case Some((futKey:Option[Key], signature:String)) => futKey flatMap {
        key =>
          if (key.securityLevel >= securityLevel && validateHash(key, signature, ctx)) {
            val timeOk = if(refreshKeysOnValid ) {
              authKeyStore.refreshTimeOutForKey(key).isDefined
            } else {
              key.timeout >= System.currentTimeMillis()
            }

            if (timeOk) {
              Some(authContextCreator(key.userId, key.securityLevel))
            } else None
          } else None
      }
      case a => None
    })
  }

  /**
   * Validates if the given signature/hash matches with the expected one
   * @param key retrived from a [[link.naicode.dhmacauth.AuthKeyStore]] instance
   * @param signature the token padded to the server in the Headers Authenticate field
   * @param ctx
   * @return true if the signature is valid for the given key
   */
  protected def validateHash(key: Key, signature: String, ctx: RequestContext):Boolean = {
    val methode = ctx.request.method.name
    val uri = ctx.request.uri.toString()
    val nhash = Hmac("HmacSHA256").run(s"$methode:$uri:dateval:", key.key)
    if (signature == nhash.asString) true
    else false
  }

  /**
   * used to retrieve the needed information from the given HttpCredentials.
   * It returns key as option so that a possible deriving class can add
   * a special handling for a ident not corresponding to any Key.
   * (e.g. some automatic Attack detection)
   * @param cred normaly a instance of Some([[spray.http.GenericHttpCredentials]])
   * @return key/signature extracted from the credentials
   */
  protected def extractInfo(cred: Option[HttpCredentials]):Option[(Option[Key], String)] = {
    cred match {
      case Some(GenericHttpCredentials("dHMACSignature", token, _)) =>
          val parts = token.split(":")
          if (parts.length == 2) {
            Try(parts(0).toInt) match {
              case Success(ident:Int) =>
                Some((authKeyStore.queryForIdent(ident), parts(1)))
              case _ => None
            }
          } else None
      case _ => None
    }

  }

  /**
   * returns the challenge responded with if authentication fails
   * (means the WWW-Authenticate field) including a parameter
   * containing the needed securty level
   * @param httpRequest the (failed) request
   * @return WWW-Authenticate challange
   */
  override def getChallengeHeaders(httpRequest: HttpRequest): List[HttpHeader] =
    `WWW-Authenticate`(HttpChallenge(scheme="dHMACSignature", realm=realm, params=Map("level" -> securityLevel.toString))) :: Nil

  /**
   * returns a new DHmacAuthenticator with a different value for
   * the `refreshKeysOnValid` field
   * @param doit_? enable feature
   * @return a new instance of this.type 
   */
  def withRefreshTimeoutOnValideKeyUsage(doit_? :Boolean): DHmacAuthenticator[U] = {
    if (doit_? == refreshKeysOnValid) this
    else `new`(authKeyStore, authContextCreator, realm, securityLevel, doit_?)
  }

  /**
   * returns a new DHmacAuthenticator with a different value for
   * the securityLevel
   * @param nlevel the new securityLevel
   * @return a new instance of this.type 
   */
  def withSecurityLevel(nlevel: Byte) =
    `new`(authKeyStore,authContextCreator, realm, nlevel, refreshKeysOnValid)

  /**
   * returns a new DHmacAuthenticator with a different realm
   * @param newRealm the new realm
   * @return a new instance of this.type 
   */
  def withRealm(newRealm: String) =
    `new`(authKeyStore, authContextCreator, newRealm, securityLevel, refreshKeysOnValid)

  /**
   * override this method if you subclass DHmacAuthenticator
   * to prevent the withXXX methods from "unexpecicly"
   * downgrading the class.
   *
   * Be WARNED that not overriding this method when deriving from
   * this class will end up in a ClassCastException whenever a
   * withXXX Methode is called. It is expected that subclasses
   * will also provide this behaviour.
   *
   * @param authKeyStore new KeyStore
   * @param authContexCreator new contex creator
   * @param realm new realm
   * @param slevel new securityLevel
   * @param refreshKeys enable(true)/disable(false) refreshKeys
   * @return a new instance of this.type 
   */
  protected def `new`(authKeyStore: AuthKeyStore, authContexCreator: AuthContextCreator[U],
                      realm: String, slevel: Byte, refreshKeys:Boolean):this.type =
    new DHmacAuthenticator[U](authKeyStore, authContexCreator, realm, slevel, refreshKeys).asInstanceOf[this.type]
  
    
}



object DHMACAuth {

  /**
   * this method provides a simple way to provide all the objects
   * often shared between DHmacAuthenticator instances implicitly.
   * It does not provide fields for securityLevel and
   * refreshKeyOnValide. Use withXXX instead.
   */
  def apply[T](realm: String)(implicit cc:AuthContextCreator[T], aq: AuthKeyStore, ex: ExecutionContext): DHmacAuthenticator[T] =
    new DHmacAuthenticator[T](aq, cc, realm)
}
