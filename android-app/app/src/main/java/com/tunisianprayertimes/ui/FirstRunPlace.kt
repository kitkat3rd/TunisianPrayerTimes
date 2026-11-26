package com.tunisianprayertimes.ui

import android.content.Context
import com.tunisianprayertimes.CitySearch
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.LocalityRepository
import com.tunisianprayertimes.PlaceCatalogue
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import com.tunisianprayertimes.withAvailablePrayerSource
import java.io.Serializable

/** Only the selected catalogue keys or the single resolved GPS match enter saved instance state. */
internal sealed interface FirstRunPlace : Serializable {
    val name: String?
    suspend fun resolve(context: Context): FirstRunPlaceChoice?

    data class Local(val id: String, val reference: String?, override val name: String) : FirstRunPlace {
        override suspend fun resolve(context: Context): FirstRunPlaceChoice? {
            val row = LocalityRepository.manualSelection(context, id, reference) ?: return null
            val sources = GouvernoratRepository.loadAllDelegations(context)
            return withAvailablePrayerSource(row, sources)?.let(FirstRunPlaceChoice::Local)
        }
    }

    data class City(val id: Long, override val name: String) : FirstRunPlace {
        override suspend fun resolve(context: Context): FirstRunPlaceChoice? {
            val row = PlaceCatalogue.get(context).byId(id)?.takeIf { it.listed } ?: return null
            return FirstRunPlaceChoice.Abroad(PlaceResolution.placeFromCatalogue(context, row), name, fromGps = false)
        }
    }

    sealed interface Match : FirstRunPlace {
        val region: String?
    }

    data class NearbyLocal(
        val delegationId: Int,
        val localityId: String?,
        override val name: String,
        override val region: String?,
    ) : Match {
        override suspend fun resolve(context: Context): FirstRunPlaceChoice? {
            val delegation = GouvernoratRepository.findDelegationById(context, delegationId) ?: return null
            val locality = localityId?.let { id ->
                LocalityRepository.loadAll(context).firstOrNull { it.id == id }?.copy(delegationId = delegation.id)
                    ?: return null
            }
            return FirstRunPlaceChoice.Gps(DelegationLocationResult.Success(delegation, locality))
        }
    }

    data class NearbyCity(
        val latE5: Int,
        val lngE5: Int,
        val elevationM: Int?,
        val zoneId: String,
        val zoneSource: ZoneSource,
        val countryCode: String?,
        val catalogueId: Long?,
        val admin1: String?,
        val admin2: String?,
        override val name: String?,
    ) : Match {
        override val region: String? get() = countryCode?.let(CitySearch::countryName)

        override suspend fun resolve(context: Context) = FirstRunPlaceChoice.Abroad(
            PrayerPlace.Coordinates(latE5, lngE5, elevationM, zoneId, zoneSource, countryCode, catalogueId, admin1, admin2),
            name, fromGps = true,
        )
    }

    data object Browse : FirstRunPlace {
        override val name: String? get() = null
        override suspend fun resolve(context: Context): FirstRunPlaceChoice {
            val (place, label) = PlaceResolution.provisionalPlace(context)
            return FirstRunPlaceChoice.Browse(place, label)
        }
    }
}

internal fun DelegationLocationResult.firstRunMatch(): FirstRunPlace.Match? = when (this) {
    is DelegationLocationResult.Success -> {
        val name = locality?.name ?: delegation.nomAr
        val region = listOf(delegation.nomAr, delegation.gouvernoratName)
            .filter { it.isNotBlank() && it != name }.distinct().joinToString("، ")
        FirstRunPlace.NearbyLocal(delegation.id, locality?.id, name, region)
    }
    is DelegationLocationResult.Abroad -> with(place) {
        FirstRunPlace.NearbyCity(latE5, lngE5, elevationM, zoneId, zoneSource, countryCode, catalogueId, admin1, admin2, label)
    }
    else -> null
}
