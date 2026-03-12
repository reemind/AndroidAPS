package app.aaps.plugins.aps.betacell

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import javax.inject.Provider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class BetaCellPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val apsResultProvider: Provider<APSResult>,
    private val rxBus: RxBus,
    private val iobCobCalculator: IobCobCalculator
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginName(R.string.betacell_plugin_name)
        .shortName(R.string.betacell_short_name)
        .preferencesId(R.xml.pref_betacell)
        .description(R.string.betacell_description),
    aapsLogger,
    rh
), APS {

    override val algorithm: APSResult.Algorithm = APSResult.Algorithm.SMB
    override var lastAPSResult: APSResult? = null
    override var lastAPSRun: Long = 0L
    private var caState: Double = 0.0

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override fun isEnabled(): Boolean = isEnabled(PluginType.APS)

    override fun configuration(): JSONObject = JSONObject().apply {
        put(BooleanKey.BetaCellOpenLoop.key,      preferences.get(BooleanKey.BetaCellOpenLoop))
        put(DoubleKey.BetaCellTargetBg.key,       preferences.get(DoubleKey.BetaCellTargetBg))
        put(DoubleKey.BetaCellHypo.key,           preferences.get(DoubleKey.BetaCellHypo))
        put(DoubleKey.BetaCellHyper.key,          preferences.get(DoubleKey.BetaCellHyper))
        put(DoubleKey.BetaCellBasalPhysio.key,    preferences.get(DoubleKey.BetaCellBasalPhysio))
        put(DoubleKey.BetaCellHepatic.key,        preferences.get(DoubleKey.BetaCellHepatic))
        put(DoubleKey.BetaCellIobTau.key,         preferences.get(DoubleKey.BetaCellIobTau))
        put(BooleanKey.BetaCellUseNonLinear.key,  preferences.get(BooleanKey.BetaCellUseNonLinear))
        put(DoubleKey.BetaCellSigmoidSlope.key,   preferences.get(DoubleKey.BetaCellSigmoidSlope))
        put(DoubleKey.BetaCellSigmoidCenter.key,  preferences.get(DoubleKey.BetaCellSigmoidCenter))
        put(DoubleKey.BetaCellMaxSecretion.key,   preferences.get(DoubleKey.BetaCellMaxSecretion))
        put(DoubleKey.BetaCellCaDecayBraked.key,  preferences.get(DoubleKey.BetaCellCaDecayBraked))
    }

    override fun applyConfiguration(configuration: JSONObject) {
        if (configuration.has(BooleanKey.BetaCellOpenLoop.key))
            preferences.put(BooleanKey.BetaCellOpenLoop,
                configuration.getBoolean(BooleanKey.BetaCellOpenLoop.key))
        if (configuration.has(DoubleKey.BetaCellTargetBg.key))
            preferences.put(DoubleKey.BetaCellTargetBg,
                configuration.getDouble(DoubleKey.BetaCellTargetBg.key))
        if (configuration.has(DoubleKey.BetaCellHypo.key))
            preferences.put(DoubleKey.BetaCellHypo,
                configuration.getDouble(DoubleKey.BetaCellHypo.key))
        if (configuration.has(DoubleKey.BetaCellHyper.key))
            preferences.put(DoubleKey.BetaCellHyper,
                configuration.getDouble(DoubleKey.BetaCellHyper.key))
        if (configuration.has(DoubleKey.BetaCellBasalPhysio.key))
            preferences.put(DoubleKey.BetaCellBasalPhysio,
                configuration.getDouble(DoubleKey.BetaCellBasalPhysio.key))
        if (configuration.has(DoubleKey.BetaCellHepatic.key))
            preferences.put(DoubleKey.BetaCellHepatic,
                configuration.getDouble(DoubleKey.BetaCellHepatic.key))
        if (configuration.has(DoubleKey.BetaCellIobTau.key))
            preferences.put(DoubleKey.BetaCellIobTau,
                configuration.getDouble(DoubleKey.BetaCellIobTau.key))
        if (configuration.has(BooleanKey.BetaCellUseNonLinear.key))
            preferences.put(BooleanKey.BetaCellUseNonLinear,
                configuration.getBoolean(BooleanKey.BetaCellUseNonLinear.key))
        if (configuration.has(DoubleKey.BetaCellSigmoidSlope.key))
            preferences.put(DoubleKey.BetaCellSigmoidSlope,
                configuration.getDouble(DoubleKey.BetaCellSigmoidSlope.key))
        if (configuration.has(DoubleKey.BetaCellSigmoidCenter.key))
            preferences.put(DoubleKey.BetaCellSigmoidCenter,
                configuration.getDouble(DoubleKey.BetaCellSigmoidCenter.key))
        if (configuration.has(DoubleKey.BetaCellMaxSecretion.key))
            preferences.put(DoubleKey.BetaCellMaxSecretion,
                configuration.getDouble(DoubleKey.BetaCellMaxSecretion.key))
        if (configuration.has(DoubleKey.BetaCellCaDecayBraked.key))
            preferences.put(DoubleKey.BetaCellCaDecayBraked,
                configuration.getDouble(DoubleKey.BetaCellCaDecayBraked.key))
    }

    internal fun prefs(): BetaCellPrefs = BetaCellPrefs(
        targetBg        = preferences.get(DoubleKey.BetaCellTargetBg),
        hypoBg          = preferences.get(DoubleKey.BetaCellHypo).coerceAtLeast(55.0),
        hyperBg         = preferences.get(DoubleKey.BetaCellHyper),
        basalPhysio     = preferences.get(DoubleKey.BetaCellBasalPhysio),
        hepatic         = preferences.get(DoubleKey.BetaCellHepatic),
        iobTauMin       = preferences.get(DoubleKey.BetaCellIobTau),
        isfMin          = preferences.get(DoubleKey.BetaCellIsfMin),
        isfMax          = preferences.get(DoubleKey.BetaCellIsfMax),
        isfWindowH      = preferences.get(IntKey.BetaCellIsfWindowH),
        slopeBrakeT     = preferences.get(DoubleKey.BetaCellSlopeBrakeT),
        slopeBrakeF     = preferences.get(DoubleKey.BetaCellSlopeBrakeF),
        smbEnabled      = preferences.get(BooleanKey.BetaCellSmbEnabled),
        smbMax          = preferences.get(DoubleKey.BetaCellSmbMax),
        smbOffset       = preferences.get(DoubleKey.BetaCellSmbOffset),
        openLoopOnly    = preferences.get(BooleanKey.BetaCellOpenLoop),
        debugMode       = preferences.get(BooleanKey.BetaCellDebug),
        hypoAlertMargin = preferences.get(DoubleKey.BetaCellHypoAlertMargin),
        hypoRapidSlope  = preferences.get(DoubleKey.BetaCellHypoRapidSlope),
        useNonLinear    = preferences.get(BooleanKey.BetaCellUseNonLinear),
        sigmoidSlope    = preferences.get(DoubleKey.BetaCellSigmoidSlope),
        sigmoidCenter   = preferences.get(DoubleKey.BetaCellSigmoidCenter),
        maxSecretion    = preferences.get(DoubleKey.BetaCellMaxSecretion),
        caDecayBraked   = preferences.get(DoubleKey.BetaCellCaDecayBraked)
    )

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        val p = prefs()
        aapsLogger.debug(LTag.APS, "BetaCell prefs: $p")
        aapsLogger.debug(LTag.APS, "BetaCellPlugin [$initiator] mode=${if(p.useNonLinear) "SIGMOID2" else "LINEAR"}")

        profileFunction.getProfile() ?: run {
            aapsLogger.error(LTag.APS, "No profile — aborting"); return
        }

        val gs = glucoseStatusCalculatorSMB.getGlucoseStatusData(false) ?: run {
            aapsLogger.warn(LTag.APS, "No CGM data"); return
        }

        val windowMs  = p.isfWindowH * 60 * 60 * 1000L
        val cutoff    = System.currentTimeMillis() - windowMs
        val bgHistory = iobCobCalculator.ads.bgReadings
            .filter { it.timestamp >= cutoff && it.value > 39.0 }
            .map    { it.value }

        val calibratedIsf = IsfCalibrator(aapsLogger).calibrate(bgHistory)

        val result = calcBetaSecretion(
            bg      = gs.glucose,
            bgDelta = gs.delta,
            dtMin   = 5.0,
            isf     = calibratedIsf,
            p       = p
        )

        val rt = RT(
            algorithm         = APSResult.Algorithm.SMB,
            runningDynamicIsf = false,
            timestamp         = System.currentTimeMillis(),
            bg                = gs.glucose,
            rate              = result.rate,
            units             = result.smb,
            duration          = result.duration,
            deliverAt         = System.currentTimeMillis(),
            reason            = StringBuilder(result.reason)
        )
        lastAPSResult = apsResultProvider.get().with(rt)
        lastAPSRun    = System.currentTimeMillis()
        rxBus.send(EventAPSCalculationFinished())
        rxBus.send(EventOpenAPSUpdateGui())

        if (p.openLoopOnly) {
            aapsLogger.info(LTag.APS,
                "[OPEN LOOP] rate=${result.rate} smb=${result.smb} zone=${result.zone}")
            return
        }
        aapsLogger.info(LTag.APS,
            "BetaCell -> rate=${result.rate} U/h | smb=${result.smb} U | zone=${result.zone}")
    }

    internal fun calcBetaSecretion(
        bg: Double, bgDelta: Double, dtMin: Double,
        isf: Double, p: BetaCellPrefs
    ): BetaCellApsResult {

        // -- Guard hypo absolu ------------------------------------------------
        if (bg < p.hypoBg) {
            if (p.useNonLinear) caState = (caState * 0.50).coerceAtLeast(0.0)
            aapsLogger.warn(LTag.APS, "HYPO guard: BG=${bg.roundToInt()} -> 0 U")
            return BetaCellApsResult().also { r ->
                r.rate     = 0.0; r.smb = 0.0
                r.reason   = "HYPO guard: ${bg.roundToInt()} < ${p.hypoBg.roundToInt()} mg/dL"
                r.isf_used = isf; r.zone = GlucoseZone.HYPO
                r.isTempBasalRequested = false
            }
        }

        // -- Pente lissée 3 points --------------------------------------------
        val bgList = iobCobCalculator.ads.getBgReadingsDataTableCopy()
        val slope  = when {
            bgList.size >= 3 -> (bgList[0].value - bgList[2].value) / 10.0
            bgList.size >= 2 -> (bgList[0].value - bgList[1].value) / 5.0
            else             -> bgDelta / dtMin
        }

        // -- IOB total = bolus + basal actif ----------------------------------
        val iobTotal = iobCobCalculator.calculateIobFromBolus().iob +
            iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().iob

        val bgIn30min = bg + slope * 30.0
        val hypoAlert = p.hypoBg + p.hypoAlertMargin

        // -- Guard hypo prédictif ---------------------------------------------
        val predictiveHypo = bgIn30min < hypoAlert &&
            (iobTotal > 0.0 || slope < p.hypoRapidSlope)
        if (predictiveHypo) {
            if (p.useNonLinear) caState = (caState * 0.60).coerceAtLeast(0.0)
            aapsLogger.warn(LTag.APS,
                "PREDICTIVE HYPO: BG=${bg.roundToInt()} " +
                "slope=${"%.2f".format(slope)} BGin30=${bgIn30min.roundToInt()} " +
                "IOB=${"%.2f".format(iobTotal)} -> 0 U")
            return BetaCellApsResult().also { r ->
                r.rate       = 0.0; r.smb = 0.0
                r.slope_used = slope; r.isf_used = isf
                r.zone       = GlucoseZone.HYPO
                r.isTempBasalRequested = false
                r.reason     = "Predictive hypo: BG=${bg.roundToInt()} " +
                    "slope=${"%.2f".format(slope)} BGin30=${bgIn30min.roundToInt()} " +
                    "< ${hypoAlert.roundToInt()} IOBtotal=${"%.2f".format(iobTotal)}U"
            }
        }

        // -- basalFactor gradué (commun aux deux modes) -----------------------
        val safetyMargin = p.hypoAlertMargin.coerceAtLeast(1.0)
        val basalFactor  = when {
            bg < p.hypoBg + 5.0                  -> 0.0
            bgIn30min < hypoAlert                -> 0.0
            bgIn30min < hypoAlert + safetyMargin ->
                ((bgIn30min - hypoAlert) / safetyMargin).coerceIn(0.0, 1.0)
            else                                 -> 1.0
        }

        val braked = slope < p.slopeBrakeT

        return if (p.useNonLinear)
            calcNonLinear(bg, slope, dtMin, isf, iobTotal, bgIn30min, hypoAlert, basalFactor, braked, p)
        else
            calcLinear(bg, slope, dtMin, isf, iobTotal, bgIn30min, hypoAlert, basalFactor, braked, p)
    }

    // =========================================================================
    // MODE LINEAIRE
    // =========================================================================
    private fun calcLinear(
        bg: Double, slope: Double, dtMin: Double, isf: Double,
        iobTotal: Double, bgIn30min: Double, hypoAlert: Double,
        basalFactor: Double, braked: Boolean, p: BetaCellPrefs
    ): BetaCellApsResult {
        var beta = if (bg > p.targetBg) ((bg - p.targetBg) / isf) * (dtMin / 60.0) else 0.0
        if (braked) beta *= p.slopeBrakeF
        beta += p.basalPhysio * (dtMin / 60.0) * basalFactor

        val systemicInsulin = beta * (1.0 - p.hepatic)
        val rate = max(0.0, systemicInsulin / (dtMin / 60.0))

        val smbAllowed = p.smbEnabled
            && bg > p.targetBg + p.smbOffset
            && bgIn30min > hypoAlert
            && iobTotal < p.smbMax * 3.0
        val smb = if (smbAllowed) min(0.3 * systemicInsulin, p.smbMax) else 0.0

        return BetaCellApsResult().also { r ->
            r.rate = rate; r.smb = smb
            r.isTempBasalRequested = rate > 0.0
            r.duration = 30
            r.betaSecretion   = beta
            r.systemicInsulin = systemicInsulin
            r.isf_used        = isf
            r.slope_used      = slope
            r.zone            = zoneOf(bg, p)
            r.reason          = buildReasonLinear(
                bg, slope, isf, beta, systemicInsulin,
                p, braked, basalFactor, bgIn30min, iobTotal)
        }
    }

    // =========================================================================
    // MODE SIGMOIDE² + CaState
    // =========================================================================
    private fun calcNonLinear(
        bg: Double, slope: Double, dtMin: Double, isf: Double,
        iobTotal: Double, bgIn30min: Double, hypoAlert: Double,
        basalFactor: Double, braked: Boolean, p: BetaCellPrefs
    ): BetaCellApsResult {

        // 1. Activation sigmoide²
        val actRaw     = 1.0 / (1.0 + exp(-p.sigmoidSlope * (bg - p.sigmoidCenter)))
        val activation = actRaw * actRaw

        // 2. Memoire calcique Ca2+ (scope local — pas de variable globale ambigue)
        val caDecay = if (braked) p.caDecayBraked else 0.85
        caState = (caState * caDecay + activation * 0.15).coerceIn(0.0, 1.0)

        // 3. Secretion portale
        var beta = p.maxSecretion * activation * caState * (dtMin / 60.0)

        // 4. Frein immediat cycle actuel
        if (braked) beta *= p.slopeBrakeF

        // 5. Resistance en hyperglycemie severe
        val resistanceFactor = when {
            bg > 250.0 -> 0.70
            bg > 200.0 -> 0.85
            else       -> 1.0
        }
        beta *= resistanceFactor

        // 6. Plafond absolu
        beta = beta.coerceAtMost(p.maxSecretion * (dtMin / 60.0))

        // 7. Basal physiologique residuel
        beta += p.basalPhysio * (dtMin / 60.0) * basalFactor

        // 8. Extraction hepatique dynamique (renommee pour eviter confusion avec p.hepatic)
        val hepaticEffective = (p.hepatic - 0.001 * (bg - 100.0)).coerceIn(0.30, 0.70)

        val systemicInsulin = beta * (1.0 - hepaticEffective)
        val rate = max(0.0, systemicInsulin / (dtMin / 60.0))

        // 9. SMB — bloque en hyper severe
        val smbAllowed = p.smbEnabled
            && bg > p.targetBg + p.smbOffset
            && bg < p.hyperBg
            && bgIn30min > hypoAlert
            && iobTotal < p.smbMax * 3.0
        val smb = if (smbAllowed) min(0.3 * systemicInsulin, p.smbMax) else 0.0

        return BetaCellApsResult().also { r ->
            r.rate = rate; r.smb = smb
            r.isTempBasalRequested = rate > 0.0
            r.duration = 30
            r.betaSecretion   = beta
            r.systemicInsulin = systemicInsulin
            r.isf_used        = isf
            r.slope_used      = slope
            r.zone            = zoneOf(bg, p)
            r.reason          = buildReasonNonLinear(
                bg, slope, beta, systemicInsulin,
                activation, caState, caDecay, hepaticEffective,
                p, braked, basalFactor, bgIn30min, iobTotal, resistanceFactor)
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
    private fun zoneOf(bg: Double, p: BetaCellPrefs): GlucoseZone = when {
        bg < p.hypoBg  -> GlucoseZone.HYPO
        bg > p.hyperBg -> GlucoseZone.HYPER
        else           -> GlucoseZone.TARGET
    }

    private fun buildReasonLinear(
        bg: Double, slope: Double, isf: Double,
        beta: Double, systemic: Double,
        p: BetaCellPrefs, braked: Boolean,
        basalFactor: Double, bgIn30min: Double, iobTotal: Double
    ): String = buildString {
        append("LINEAR BG=${bg.roundToInt()} tgt=${p.targetBg.roundToInt()} ")
        append("ISF=${"%.1f".format(isf)} slope=${"%.2f".format(slope)} ")
        append("BGin30=${bgIn30min.roundToInt()} IOB=${"%.2f".format(iobTotal)}U ")
        if (braked)            append("[brake*${p.slopeBrakeF}] ")
        if (basalFactor < 1.0) append("[basal*${"%.2f".format(basalFactor)} PRE-ALERT] ")
        append("b=${"%.3f".format(beta)}U sys=${"%.3f".format(systemic)}U ")
        if (p.openLoopOnly)    append("[OPEN_LOOP]")
    }

    private fun buildReasonNonLinear(
        bg: Double, slope: Double,
        beta: Double, systemic: Double,
        activation: Double, caState: Double,
        caDecay: Double, hepaticEffective: Double,
        p: BetaCellPrefs, braked: Boolean,
        basalFactor: Double, bgIn30min: Double,
        iobTotal: Double, resistanceFactor: Double
    ): String = buildString {
        append("SIGMOID2 BG=${bg.roundToInt()} tgt=${p.targetBg.roundToInt()} ")
        append("slope=${"%.2f".format(slope)} ")
        append("BGin30=${bgIn30min.roundToInt()} IOB=${"%.2f".format(iobTotal)}U ")
        append("act=${"%.3f".format(activation)} Ca=${"%.3f".format(caState)} ")
        append("caDecay=${"%.2f".format(caDecay)} hepEff=${"%.2f".format(hepaticEffective)} ")
        if (braked)                 append("[brake*${p.slopeBrakeF}] ")
        if (basalFactor < 1.0)      append("[basal*${"%.2f".format(basalFactor)} PRE-ALERT] ")
        if (resistanceFactor < 1.0) append("[resist*${"%.2f".format(resistanceFactor)}] ")
        append("b=${"%.3f".format(beta)}U sys=${"%.3f".format(systemic)}U ")
        if (p.openLoopOnly)         append("[OPEN_LOOP]")
    }

    override fun addPreferenceScreen(
        preferenceManager: PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {
        if (requiredKey != null) return
        with(parent) {
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellTargetBg,
                title = R.string.betacell_pref_target_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellHypo,
                title = R.string.betacell_pref_hypo_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellHyper,
                title = R.string.betacell_pref_hyper_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellBasalPhysio,
                title = R.string.betacell_pref_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellHepatic,
                title = R.string.betacell_pref_hepatic_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellIobTau,
                title = R.string.betacell_pref_iob_tau_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellIsfMin,
                title = R.string.betacell_pref_isf_min_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellIsfMax,
                title = R.string.betacell_pref_isf_max_title))
            addPreference(AdaptiveIntPreference(ctx = context,
                intKey = IntKey.BetaCellIsfWindowH,
                title = R.string.betacell_pref_isf_window_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellSlopeBrakeT,
                title = R.string.betacell_pref_brake_threshold_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellSlopeBrakeF,
                title = R.string.betacell_pref_brake_factor_title))
            addPreference(AdaptiveSwitchPreference(ctx = context,
                booleanKey = BooleanKey.BetaCellSmbEnabled,
                title = R.string.betacell_pref_smb_enabled_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellSmbMax,
                title = R.string.betacell_pref_smb_max_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellSmbOffset,
                title = R.string.betacell_pref_smb_offset_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellHypoAlertMargin,
                title = R.string.betacell_pref_hypo_alert_margin_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellHypoRapidSlope,
                title = R.string.betacell_pref_hypo_rapid_slope_title))
            addPreference(AdaptiveSwitchPreference(ctx = context,
                booleanKey = BooleanKey.BetaCellUseNonLinear,
                title = R.string.betacell_pref_nonlinear_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellSigmoidSlope,
                title = R.string.betacell_pref_sigmoid_slope_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellSigmoidCenter,
                title = R.string.betacell_pref_sigmoid_center_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellMaxSecretion,
                title = R.string.betacell_pref_max_secretion_title))
            addPreference(AdaptiveDoublePreference(ctx = context,
                doubleKey = DoubleKey.BetaCellCaDecayBraked,
                title = R.string.betacell_pref_ca_decay_braked_title))
            addPreference(AdaptiveSwitchPreference(ctx = context,
                booleanKey = BooleanKey.BetaCellOpenLoop,
                title = R.string.betacell_pref_open_loop_title))
            addPreference(AdaptiveSwitchPreference(ctx = context,
                booleanKey = BooleanKey.BetaCellDebug,
                title = R.string.betacell_pref_debug_title))
        }
    }
}
