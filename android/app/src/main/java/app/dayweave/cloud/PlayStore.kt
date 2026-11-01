package app.dayweave.cloud

import android.app.Activity
import android.content.Context
import app.dayweave.core.tr
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 拾日云订阅方案。商品 ID 与 App Store、服务端 plans.mjs 一致：app.dayweave.cloud.<档位>.<monthly|yearly>。 */
/** 年付 = 10 个月的价格；专业和全天只有月付（yearlyUsd 为 null）。 */
enum class Plan(val hours: Int, val monthlyUsd: String, val yearlyUsd: String?, private val zh: String, private val en: String) {
    LITE(5, "$19.99", "$199.99", "轻享", "Lite"),
    PLUS(30, "$99.99", "$999.99", "标准", "Plus"),
    PRO(70, "$199.99", null, "专业", "Pro"),
    MAX(180, "$499.99", null, "全天", "Max"),
    ;

    val label get() = tr(zh, en)

    fun productId(yearly: Boolean) = "app.dayweave.cloud.${name.lowercase()}.${if (yearly) "yearly" else "monthly"}"

    /** 按 30 天平均到每天的人声时长。 */
    val perDay get() = (hours * 60 / 30).let { m ->
        if (m >= 60) "%.1f".format(m / 60.0).removeSuffix(".0").let { tr("约每天 $it 小时", "~$it h a day") } else tr("约每天 $m 分钟", "~$m min a day")
    }

    companion object {
        /** 服务端账户的 label 形如 sub:plus。 */
        fun ofLabel(label: String) = entries.firstOrNull { "sub:${it.name.lowercase()}" == label }
        fun ofProduct(productId: String) = entries.firstOrNull { productId.startsWith("app.dayweave.cloud.${it.name.lowercase()}.") }
    }
}

/** 商店里的一个方案：本地化价格来自 Google Play。 */
data class StoreOffer(val plan: Plan, val yearly: Boolean, val price: String, internal val details: ProductDetails, internal val offerToken: String)

/**
 * Google Play 订阅。买完或恢复时把 purchaseToken 交给拾日云核验，换回凭证（DS-…），之后和授权码走同一套额度。
 * 确认（acknowledge）由服务端在核验通过后完成。
 */
class PlayStore(context: Context, private val cloud: CloudApi, private val scope: CoroutineScope) : PurchasesUpdatedListener {
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private val _offers = MutableStateFlow<List<StoreOffer>>(emptyList())
    val offers: StateFlow<List<StoreOffer>> = _offers

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 最近一次购买或恢复的结果说明；null 表示没有要提示的。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    /** 读取方案价格；商店里还没有上架时列表为空，界面退回显示美元标价。 */
    fun load() = scope.launch(Dispatchers.IO) {
        if (!connect()) return@launch
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            Plan.entries.flatMap { listOfNotNull(it.productId(false), it.yearlyUsd?.let { _ -> it.productId(true) }) }.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
            },
        ).build()
        val result = client.queryProductDetails(params)
        _offers.value = result.productDetailsList.orEmpty().mapNotNull { details ->
            val plan = Plan.ofProduct(details.productId) ?: return@mapNotNull null
            val yearly = details.productId.endsWith(".yearly")
            val offer = details.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
            val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: if (yearly) plan.yearlyUsd.orEmpty() else plan.monthlyUsd
            StoreOffer(plan, yearly, price, details, offer.offerToken)
        }.sortedBy { it.plan.ordinal }
    }

    fun purchase(activity: Activity, offer: StoreOffer) {
        _message.value = null
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.details).setOfferToken(offer.offerToken).build()),
        ).build()
        // 已有订阅时 Google Play 会提示换方案；服务端靠 linkedPurchaseToken 沿用原来的凭证。
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch(Dispatchers.IO) { redeem(purchases.orEmpty(), quiet = false) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> _message.value = tr("购买没有完成（${result.responseCode}）", "Purchase didn't go through (${result.responseCode})")
        }
    }

    /** 恢复购买：换手机或重装后，把这个 Google 账号下有效的订阅重新交给拾日云核验。 */
    fun restore(quiet: Boolean = false) = scope.launch(Dispatchers.IO) {
        if (!connect()) {
            if (!quiet) _message.value = tr("连不上 Google Play", "Couldn't reach Google Play")
            return@launch
        }
        val owned = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        val purchases = owned.purchasesList
        if (purchases.isEmpty()) {
            if (!quiet) _message.value = tr("这个 Google 账号下没有拾日云订阅", "No Dayweave Cloud subscription on this Google account")
            return@launch
        }
        redeem(purchases, quiet)
    }

    private fun redeem(purchases: List<Purchase>, quiet: Boolean) {
        val paid = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (paid.isEmpty()) {
            if (purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
                _message.value = tr("付款处理中，完成后会自动开通", "Payment is pending; Cloud turns on once it clears")
            }
            return
        }
        _busy.value = true
        // 多笔时以最新的一笔为准
        val error = cloud.subscribe("google", paid.maxBy { it.purchaseTime }.purchaseToken)
        _busy.value = false
        if (error != null || !quiet) _message.value = error ?: tr("拾日云已开通", "Dayweave Cloud is on")
    }
}
