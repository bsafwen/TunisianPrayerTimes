package com.tunisianprayertimes.platform

import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Delegation

/**
 * Platform abstraction for loading gouvernorat/delegation data.
 */
expect object GouvernoratLoader {
    fun loadAll(): List<Gouvernorat>
    fun loadAllDelegations(): List<Delegation>
    fun findDelegationById(id: Int): Delegation?
    fun findNearestDelegation(lat: Double, lng: Double): Delegation?
}
