package com.tunisianprayertimes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.CitySearch
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.Locality
import com.tunisianprayertimes.LocalityPickerCatalog
import com.tunisianprayertimes.LocalityRepository
import com.tunisianprayertimes.PlaceCatalogue
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.CardBg
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private enum class LocationLookup { Idle, Permission, Locating, Denied, Unavailable }

@Composable
fun OnboardingScreen(pickerRequestId: Int? = null, onFinish: () -> Unit) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinish)
    var pickerOpen by rememberSaveable { mutableStateOf(pickerRequestId != null) }
    var handledPickerRequest by rememberSaveable { mutableStateOf(pickerRequestId) }
    var lookup by rememberSaveable { mutableStateOf(LocationLookup.Idle) }
    var match by rememberSaveable { mutableStateOf<FirstRunPlace.Match?>(null) }
    var selection by rememberSaveable { mutableStateOf<FirstRunPlace?>(null) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var preparationFailed by remember { mutableStateOf(false) }
    var preparingName by remember { mutableStateOf<String?>(null) }
    val primaryFocus = remember { FocusRequester() }
    val secondaryFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var browseFocused by remember { mutableStateOf(false) }

    fun returnKeyboardFocus(target: FocusRequester) {
        if (inputMode.inputMode == InputMode.Keyboard) pendingFocus = target
    }
    LaunchedEffect(pendingFocus, pickerOpen, windowFocused) {
        if (pickerOpen || !windowFocused) return@LaunchedEffect
        pendingFocus?.let { if (inputMode.inputMode == InputMode.Keyboard) it.requestFocus() }
        pendingFocus = null
    }

    fun cancelPreparation() {
        selection = null
        preparationFailed = false
        preparingName = null
    }
    fun openPicker() {
        cancelPreparation()
        if (lookup == LocationLookup.Permission || lookup == LocationLookup.Locating) lookup = LocationLookup.Idle
        pendingFocus = null
        pickerOpen = true
    }
    LaunchedEffect(pickerRequestId) {
        if (pickerRequestId != null && handledPickerRequest != pickerRequestId) openPicker()
        handledPickerRequest = pickerRequestId
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (lookup == LocationLookup.Permission) {
            lookup = if (grants.values.any { it }) LocationLookup.Locating else LocationLookup.Denied
            if (lookup == LocationLookup.Denied && !browseFocused) returnKeyboardFocus(secondaryFocus)
        }
    }
    fun locate() {
        if (lookup == LocationLookup.Permission || lookup == LocationLookup.Locating) return
        cancelPreparation()
        match = null
        if (DelegationLocator.hasLocationPermission(context)) {
            lookup = LocationLookup.Locating
        } else {
            lookup = LocationLookup.Permission
            permission.launch(DelegationLocator.requestedPermissions)
        }
    }
    LaunchedEffect(lookup) {
        if (lookup != LocationLookup.Locating) return@LaunchedEffect
        val result = try {
            DelegationLocator.detectNearestDelegation(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DelegationLocationResult.LocationUnavailable
        }
        ensureActive()
        if (lookup != LocationLookup.Locating) return@LaunchedEffect
        match = result.firstRunMatch()
        lookup = when {
            match != null -> LocationLookup.Idle
            result == DelegationLocationResult.PermissionDenied -> LocationLookup.Denied
            else -> LocationLookup.Unavailable
        }
        if (!browseFocused) returnKeyboardFocus(if (lookup == LocationLookup.Denied) secondaryFocus else primaryFocus)
    }
    fun select(place: FirstRunPlace) {
        lookup = LocationLookup.Idle
        pickerOpen = false
        pendingFocus = null
        preparationFailed = false
        preparingName = place.name
        selection = place
        attempt++
    }
    LaunchedEffect(selection, attempt) {
        val requested = selection ?: return@LaunchedEffect
        val choice = try {
            withContext(Dispatchers.IO) { requested.resolve(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }
        ensureActive()
        if (selection != requested) return@LaunchedEffect
        if (choice == null) {
            preparationFailed = true
            return@LaunchedEffect
        }
        val place = when (choice) {
            is FirstRunPlaceChoice.Local -> PrayerPlace.Inm(choice.locality.delegationId)
            is FirstRunPlaceChoice.Gps -> PrayerPlace.Inm(choice.result.delegation.id)
            is FirstRunPlaceChoice.Abroad -> choice.place
            is FirstRunPlaceChoice.Browse -> choice.place
        }
        preparingName = when (choice) {
            is FirstRunPlaceChoice.Browse -> choice.label ?: context.getString(R.string.first_run_tunis)
            else -> requested.name
        }
        try {
            withContext(Dispatchers.Default) { PrayerTimesRepository.warm(context, place) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            preparationFailed = true
            return@LaunchedEffect
        }
        ensureActive()
        if (selection != requested) return@LaunchedEffect
        val source = when (choice) {
            is FirstRunPlaceChoice.Local -> "manual"
            is FirstRunPlaceChoice.Gps -> "onboarding_gps"
            is FirstRunPlaceChoice.Abroad -> if (choice.fromGps) "onboarding_gps_abroad" else "manual_abroad"
            is FirstRunPlaceChoice.Browse -> "onboarding_skipped"
        }
        if (PrefsManager.completeFirstRun(context, choice)) {
            AnalyticsTracker.markDelegationSource(context, source)
        }
        finish()
    }
    BackHandler(!pickerOpen && (selection != null || match != null || lookup != LocationLookup.Idle)) {
        val returningFromLookup = match != null || lookup != LocationLookup.Idle
        cancelPreparation()
        lookup = LocationLookup.Idle
        match = null
        returnKeyboardFocus(if (returningFromLookup) secondaryFocus else primaryFocus)
    }

    val currentMatch = match
    val denied = currentMatch == null && lookup == LocationLookup.Denied
    val placeName = currentMatch?.name ?: stringResource(R.string.location_current_place)
    val matchDetails = currentMatch?.let {
        val zone = (it as? FirstRunPlace.NearbyCity)?.zoneId?.takeIf { _ -> it.name == null }
        listOfNotNull(placeName, it.region?.takeIf(String::isNotBlank), zone).joinToString(" · ")
    }
    val resolving = selection != null && !preparationFailed
    val searching = lookup == LocationLookup.Locating || lookup == LocationLookup.Permission
    val title = when {
        currentMatch != null -> stringResource(R.string.first_run_match_title)
        lookup == LocationLookup.Denied -> stringResource(R.string.first_run_denied_title)
        lookup == LocationLookup.Unavailable -> stringResource(R.string.first_run_unavailable_title)
        else -> stringResource(R.string.first_run_place_title)
    }
    val hasLookupResult = currentMatch != null || denied || lookup == LocationLookup.Unavailable
    BoxWithConstraints(
        Modifier.fillMaxSize().background(BgCream).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 504.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (hasLookupResult) {
                    Column(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painterResource(if (currentMatch != null) R.drawable.ic_location else R.drawable.ic_my_location),
                            contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(32.dp),
                        )
                        Text(title, fontSize = 28.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, color = GreenPrimaryDark)
                        if (matchDetails != null) Text(matchDetails, fontSize = 18.sp, lineHeight = 28.sp, color = TextDark)
                    }
                } else {
                    FirstRunHeader(
                        title = title,
                        description = stringResource(R.string.first_run_place_description),
                        art = FirstRunArt.Place,
                    )
                }
                Spacer(Modifier.height(24.dp))
                val surface = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                val shape = RoundedCornerShape(16.dp)
                val actionPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                if (!denied) {
                    Button(
                        onClick = { if (currentMatch != null) select(currentMatch) else openPicker() },
                        enabled = currentMatch == null || !resolving,
                        modifier = surface.focusRequester(primaryFocus)
                            .testTag(if (currentMatch != null) "onboarding_confirm_location" else "onboarding_location_pick"),
                        shape = shape,
                        colors = ButtonDefaults.buttonColors(containerColor = GreenPrimaryDark, contentColor = Color.White),
                        contentPadding = actionPadding,
                    ) {
                        PlaceActionContent(
                            icon = if (currentMatch != null) R.drawable.ic_check else R.drawable.ic_adhkar_search,
                            label = if (currentMatch != null) stringResource(R.string.first_run_show_times, placeName)
                                else stringResource(R.string.onboarding_location_pick),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // Android restores accessibility focus to the control that launched its permission dialog.
                // Keep that node focusable during the dialog and let it open the manual picker after a refusal.
                OutlinedButton(
                    onClick = { if (denied || currentMatch != null) openPicker() else locate() },
                    enabled = lookup != LocationLookup.Locating,
                    modifier = surface.focusRequester(secondaryFocus)
                        .testTag(if (denied || currentMatch != null) "onboarding_location_pick" else "onboarding_location_locate"),
                    shape = shape,
                    border = BorderStroke(1.dp, if (denied) GreenPrimaryDark else GreenPrimary.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (denied) GreenPrimaryDark else CardBg,
                        contentColor = if (denied) Color.White else GreenPrimaryDark,
                    ),
                    contentPadding = actionPadding,
                ) {
                    val label = when {
                        denied -> R.string.onboarding_location_pick
                        currentMatch != null -> R.string.first_run_choose_another
                        lookup == LocationLookup.Unavailable -> R.string.first_run_retry_location
                        else -> R.string.first_run_use_location
                    }
                    PlaceActionContent(
                        icon = if (denied || currentMatch != null) R.drawable.ic_adhkar_search else R.drawable.ic_my_location,
                        label = stringResource(label),
                    )
                }
                if (resolving || preparationFailed || lookup == LocationLookup.Locating) {
                    Spacer(Modifier.height(12.dp))
                    val progress = when {
                        preparationFailed -> stringResource(R.string.first_run_source_error)
                        searching -> stringResource(R.string.onboarding_location_locating)
                        else -> preparingName?.let { stringResource(R.string.first_run_preparing_city, it) }
                            ?: stringResource(R.string.first_run_preparing)
                    }
                    Text(progress, fontSize = 15.sp, lineHeight = 22.sp, color = TextDark,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (preparationFailed) TextButton(onClick = { selection?.let(::select) }) {
                        Text(stringResource(R.string.first_run_retry), color = GreenPrimaryDark)
                    }
                }
            }
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = CardBorder)
                TextButton(
                    onClick = { select(FirstRunPlace.Browse) },
                    enabled = selection != FirstRunPlace.Browse || preparationFailed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .onFocusChanged { browseFocused = it.isFocused }.testTag("onboarding_browse"),
                    colors = ButtonDefaults.textButtonColors(contentColor = GreenPrimaryDark),
                ) {
                    Text(
                        stringResource(R.string.first_run_browse), modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center, fontSize = 16.sp, lineHeight = 24.sp,
                    )
                }
            }
        }
    }
    if (pickerOpen) {
        FirstRunPicker(
            onDismiss = {
                pickerOpen = false
                returnKeyboardFocus(if (match != null || lookup == LocationLookup.Denied) secondaryFocus else primaryFocus)
            },
            onLocality = { select(FirstRunPlace.Local(it.id, it.manualReferenceId, it.name)) },
            onCity = { select(FirstRunPlace.City(it.ref.id, PlaceResolution.catalogueLabel(it))) },
        )
    }
}

@Composable
private fun RowScope.PlaceActionContent(@DrawableRes icon: Int, label: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(12.dp))
    Text(
        label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start,
        fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun FirstRunPicker(onDismiss: () -> Unit, onLocality: (Locality) -> Unit, onCity: (PlaceCatalogue.Row) -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var localLoading by remember { mutableStateOf(true) }
    var cityLoading by remember { mutableStateOf(true) }
    var governors by remember { mutableStateOf(emptyList<Gouvernorat>()) }
    var catalog by remember { mutableStateOf(LocalityPickerCatalog.empty) }
    var cities by remember { mutableStateOf(CitySearch.Index.EMPTY) }
    LaunchedEffect(attempt) {
        localLoading = true
        try {
            val result = withContext(Dispatchers.IO) {
                GouvernoratRepository.loadAll(context) to LocalityRepository.preparePicker(context, GouvernoratRepository.loadAllDelegations(context))
            }
            governors = result.first
            catalog = result.second
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            catalog = LocalityPickerCatalog.empty
        } finally { localLoading = false }
    }
    LaunchedEffect(attempt) {
        cityLoading = true
        try {
            cities = withContext(Dispatchers.IO) { PlaceCatalogue.get(context).pickerIndex }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cities = CitySearch.Index.EMPTY
        } finally { cityLoading = false }
    }
    val missingSource = (!localLoading && catalog.groups.isEmpty()) || (!cityLoading && cities.isEmpty)
    LocalityPickerSheet(
        catalog = catalog, gouvernorats = governors, selectedId = "", onDismiss = onDismiss, onSelect = onLocality,
        cities = cities, onSelectCity = onCity, loading = localLoading, citiesLoading = cityLoading,
        onRetrySources = if (missingSource) ({ attempt++ }) else null,
    )
}
