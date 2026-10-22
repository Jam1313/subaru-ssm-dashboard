package kg.forester.sg

import kg.forester.sg.data.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test fun newNameFirst() {
        val both = listOf("subaru-ssm-dashboard.apk", "carpulse-0.9-beta21.apk", "carpulse.apk", "subaru-ssm-dashboard-0.9-beta21.apk")
        assertEquals("carpulse.apk", Updater.pickApk(both, "v0.9-beta21"))
        assertEquals("carpulse-0.9-beta21.apk", Updater.pickApk(both - "carpulse.apk", "v0.9-beta21"))
    }

    @Test fun oldReleasesStillWork() {
        assertEquals("subaru-ssm-dashboard.apk",
            Updater.pickApk(listOf("subaru-ssm-dashboard-0.9-beta20.apk", "subaru-ssm-dashboard.apk"), "v0.9-beta20"))
        assertEquals("subaru-ssm-dashboard-0.9-beta20.apk",
            Updater.pickApk(listOf("manual.pdf", "subaru-ssm-dashboard-0.9-beta20.apk"), "v0.9-beta20"))
    }

    @Test fun otherNamesAndNone() {
        // a version suffix that does not match the tag still beats an unrelated APK
        assertEquals("carpulse-0.9-beta21-fix.apk", Updater.pickApk(listOf("other.apk", "carpulse-0.9-beta21-fix.apk"), "v0.9-beta21"))
        assertEquals("other.apk", Updater.pickApk(listOf("notes.txt", "other.apk"), "v1.0"))
        assertNull(Updater.pickApk(listOf("notes.txt"), "v1.0"))
    }
}
