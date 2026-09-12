package io.samcnpc.core.api

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcNavigationBoundsTest {
    private val bounds=NpcNavigationBounds(NpcPosition(-20.5,50.0,-16.0),NpcPosition(-2.0,60.0,3.0))
    @Test fun finiteOrderedBoundsContainTheirInclusiveEdgesAndNegativeCoordinates() {
        assertNull(bounds.validationProblem())
        assertTrue(bounds.contains(bounds.min));assertTrue(bounds.contains(bounds.max))
        assertFalse(bounds.contains(bounds.min.copy(x=-20.50001)))
        assertFalse(bounds.contains(bounds.max.copy(z=3.00001)))
        assertFalse(bounds.contains(bounds.min.copy(y=Double.NaN)))
    }
    @Test fun invertedInfiniteAndExcessiveSpansAreRejected() {
        for (bad in listOf(bounds.copy(min=bounds.max,max=bounds.min),bounds.copy(max=bounds.max.copy(x=Double.POSITIVE_INFINITY)),
            bounds.copy(min=bounds.min.copy(z=Double.NaN)),bounds.copy(max=bounds.max.copy(y=400.0)))) {
            assertNotNull(bad.validationProblem())
            assertNotNull(NpcNavigationRequest(bounds.min,bounds=bad).validationProblem())
        }
    }
    @Test fun theRequestRejectsAnOutsideDestinationButDefaultRequestsRemainCompatible() {
        assertNull(NpcNavigationRequest(bounds.min,bounds=bounds).validationProblem())
        val outside=bounds.max.copy(x=0.0)
        assertNotNull(NpcNavigationRequest(outside,bounds=bounds).validationProblem())
        assertNull(NpcNavigationRequest(outside).validationProblem())
        assertNull(NpcNavigationRequest(outside).bounds)
    }
    @Test fun legacyFacadeCannotSilentlyIgnoreBoundsButPlainRequestsStillDelegate() {
        var delegated=0
        // The test proxy models an old implementer with only the original overload. Invoke
        // the actual Java default method so an accidental fallback cannot bypass this check.
        val legacy=java.lang.reflect.Proxy.newProxyInstance(NpcFacade::class.java.classLoader,arrayOf(NpcFacade::class.java)) { proxy,method,args ->
            when {
                method.isDefault -> java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,*(args ?: emptyArray()))
                method.name == "navigateTo" && method.parameterCount == 2 -> { delegated++;NpcActionResult.accepted("legacy route") }
                else -> error("unexpected legacy facade call ${method.name}")
            }
        } as NpcFacade
        val bounded=legacy.navigateTo(NpcNavigationRequest(bounds.min,bounds=bounds))
        assertEquals(NpcActionStatus.UNSUPPORTED,bounded.status);assertEquals(0,delegated)
        val ordinary=legacy.navigateTo(NpcNavigationRequest(bounds.min))
        assertEquals(NpcActionStatus.ACCEPTED,ordinary.status);assertEquals(1,delegated)
    }

}
