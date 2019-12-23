package com.asfoundation.wallet.subscriptions

import com.asfoundation.wallet.util.isNoNetworkException
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import java.util.concurrent.TimeUnit

class SubscriptionListPresenter(
    private val view: SubscriptionListView,
    private val subscriptionInteract: SubscriptionInteract,
    private val disposables: CompositeDisposable,
    private val networkScheduler: Scheduler,
    private val viewScheduler: Scheduler
) {

  fun present() {
    loadSubscriptions()
    handleNoNetworkRetryClicks()
    handleGenericRetryClicks()
    handleItemClicks()
  }

  private fun loadSubscriptions() {
    disposables.add(
        Single.fromCallable { view.showLoading() }.subscribeOn(viewScheduler)
            .observeOn(networkScheduler)
            .flatMap { subscriptionInteract.loadSubscriptions() }
            .delay(1, TimeUnit.SECONDS)
            .subscribeOn(networkScheduler)
            .observeOn(viewScheduler)
            .doOnSuccess(this::onSubscriptions)
            .subscribe({}, { onError(it) }))
  }

  private fun handleItemClicks() {
    disposables.add(view.subscriptionClicks()
        .observeOn(viewScheduler)
        .doOnNext { view.showSubscriptionDetails(it) }
        .subscribe({}, { it.printStackTrace() }))
  }

  private fun onSubscriptions(subscriptionModel: SubscriptionModel) {
    if (subscriptionModel.isEmpty) {
      view.showNoSubscriptions()
    } else {
      view.showSubscriptions()
      view.onActiveSubscriptions(subscriptionModel.activeSubscriptions)
      view.onExpiredSubscriptions(subscriptionModel.expiredSubscriptions)
    }
  }

  private fun handleNoNetworkRetryClicks() {
    disposables.add(
        view.getRetryNetworkClicks()
            .observeOn(viewScheduler)
            .doOnNext { view.showNoNetworkRetryAnimation() }
            .delay(1, TimeUnit.SECONDS)
            .observeOn(networkScheduler)
            .doOnNext { loadSubscriptions() }
            .subscribe({}, { it.printStackTrace() }))
  }

  private fun handleGenericRetryClicks() {
    disposables.add(
        view.getRetryGenericClicks()
            .observeOn(viewScheduler)
            .doOnNext { view.showGenericRetryAnimation() }
            .delay(1, TimeUnit.SECONDS)
            .doOnNext { loadSubscriptions() }
            .subscribe({}, { it.printStackTrace() }))
  }

  private fun onError(throwable: Throwable) {
    throwable.printStackTrace()
    if (throwable.isNoNetworkException()) {
      view.showNoNetworkError()
    } else {
      view.showGenericError()
    }
  }

  fun stop() {
    disposables.clear()
  }

}