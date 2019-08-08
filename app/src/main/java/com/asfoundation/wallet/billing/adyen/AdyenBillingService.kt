package com.asfoundation.wallet.billing.adyen

import com.adyen.core.models.Payment
import com.appcoins.wallet.bdsbilling.Billing
import com.appcoins.wallet.bdsbilling.WalletService
import com.appcoins.wallet.bdsbilling.repository.TransactionStatus
import com.appcoins.wallet.bdsbilling.repository.TransactionType
import com.appcoins.wallet.bdsbilling.repository.entity.Gateway
import com.appcoins.wallet.bdsbilling.repository.entity.Transaction
import com.asfoundation.wallet.billing.BillingService
import com.asfoundation.wallet.billing.TransactionService
import com.asfoundation.wallet.billing.authorization.AdyenAuthorization
import com.asfoundation.wallet.billing.partners.AddressService
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean

class AdyenBillingService(
    private val merchantName: String,
    private val transactionService: TransactionService,
    private val walletService: WalletService,
    private val adyen: Adyen,
    private val partnerAddressService: AddressService,
    private val billing: Billing,
    private val scheduler: Scheduler
) : BillingService {

  private val relay: BehaviorRelay<AdyenAuthorization> = BehaviorRelay.create()
  private val processingPayment = AtomicBoolean()
  @Volatile
  private var adyenAuthorization: AdyenAuthorization? = null
  @Volatile
  private var transactionUid: String? = null

  override fun getAuthorization(productName: String?,
                                developerAddress: String?, payload: String?, origin: String,
                                priceValue: BigDecimal,
                                priceCurrency: String, type: String, callback: String?,
                                orderReference: String?,
                                appPackageName: String): Observable<AdyenAuthorization> {
    return relay.doOnSubscribe {
      startOrResumePayment(productName, developerAddress, payload, origin,
          priceValue, priceCurrency, type, callback, orderReference, appPackageName)
    }
        .doOnNext { this.resetProcessingFlag(it) }
  }

  override fun getAuthorization(origin: String, priceValue: BigDecimal, priceCurrency: String,
                                type: String,
                                appPackageName: String): Observable<AdyenAuthorization> {
    return getAuthorization(null, null, null, origin, priceValue, priceCurrency, type, null, null,
        appPackageName)
  }

  override fun authorize(payment: Payment, paykey: String): Completable {
    return Single.fromCallable { payment.paymentStatus == Payment.PaymentStatus.AUTHORISED }
        .flatMapCompletable { authorized ->
          walletService.getWalletAddress()
              .flatMapCompletable { walletAddress ->
                walletService.signContent(walletAddress)
                    .flatMapCompletable { signedContent ->
                      if (!processingPayment.get()) {
                        return@flatMapCompletable walletService.signContent(walletAddress)
                            .flatMapCompletable { Completable.complete() }
                      } else {
                        return@flatMapCompletable walletService.signContent(walletAddress)
                            .flatMapCompletable {
                              transactionService.finishTransaction(walletAddress, signedContent,
                                  transactionUid, paykey)
                            }
                            .andThen(Completable.fromAction { callRelay(authorized) })
                      }
                    }
              }
        }
  }

  override fun getTransactionUid(): String? {
    return transactionUid
  }

  private fun callRelay(authorized: Boolean) {
    if (authorized) {
      relay.accept(AdyenAuthorization(adyenAuthorization!!.session,
          AdyenAuthorization.Status.REDEEMED))
    } else {
      relay.accept(AdyenAuthorization(adyenAuthorization!!.session,
          AdyenAuthorization.Status.FAILED))
    }
  }

  private fun resetProcessingFlag(adyenAuthorization: AdyenAuthorization) {
    if (adyenAuthorization.isCompleted!! || adyenAuthorization.isFailed!!) {
      processingPayment.set(false)
    }
  }

  private fun startOrResumePayment(productName: String?, developerAddress: String?,
                                   payload: String?, origin: String, priceValue: BigDecimal,
                                   priceCurrency: String, type: String, callback: String?,
                                   orderReference: String?, appPackageName: String) {
    if (!processingPayment.getAndSet(true)) {
      this.adyenAuthorization = walletService.getWalletAddress()
          .flatMap { walletAddress ->
            walletService.signContent(walletAddress)
                .flatMap { signedContent ->
                  billing.getTransaction(appPackageName, productName, walletAddress, signedContent,
                      TransactionType.valueOf(type),
                      TransactionStatus.PENDING_SERVICE_AUTHORIZATION, Gateway.Name.adyen)
                      .flatMap {
                        if (it.status != Transaction.Status.INVALID_TRANSACTION) {
                          this.transactionUid = it.uid
                          transactionService.getSession(walletAddress, signedContent,
                              transactionUid)
                        } else {
                          adyen.token.flatMap { token ->
                            startPayment(productName, developerAddress, payload, origin, priceValue,
                                priceCurrency, type, callback, orderReference, appPackageName,
                                walletAddress, signedContent, token)
                          }
                        }
                      }
                      .onErrorResumeNext {
                        adyen.token.flatMap { token ->
                          startPayment(productName, developerAddress, payload, origin, priceValue,
                              priceCurrency, type, callback, orderReference, appPackageName,
                              walletAddress, signedContent, token)
                        }
                      }
                }
          }
          .map { this.newDefaultAdyenAuthorization(it) }
          .blockingGet()

      relay.accept(adyenAuthorization!!)
    }
  }

  private fun newDefaultAdyenAuthorization(session: String): AdyenAuthorization {
    return AdyenAuthorization(session, AdyenAuthorization.Status.PENDING)
  }

  private fun startPayment(productName: String?, developerAddress: String?,
                           payload: String?,
                           origin: String, priceValue: BigDecimal, priceCurrency: String,
                           type: String, callback: String?,
                           orderReference: String?, appPackageName: String, walletAddress: String,
                           signedContent: String, token: String): Single<String> {
    return Single.zip<String, String, Single<String>>(
        partnerAddressService.getStoreAddressForPackage(appPackageName),
        partnerAddressService.getOemAddressForPackage(appPackageName),
        BiFunction { storeAddress, oemAddress ->
          transactionService.createTransaction(
              walletAddress, signedContent, token, merchantName, payload,
              productName,
              developerAddress, storeAddress, oemAddress, origin, walletAddress,
              priceValue, priceCurrency, type, callback, orderReference)
        })
        .flatMap { transactionUid -> transactionUid }
        .doOnSuccess { transactionUid -> this.transactionUid = transactionUid }
        .flatMap { transactionUid ->
          transactionService.getSession(walletAddress,
              signedContent, transactionUid)
        }
  }
}