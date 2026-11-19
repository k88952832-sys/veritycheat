package dev.aero.menu

// Shared in-memory state for the running app process. Set by MainActivity
// whenever the person changes the target package field; read by
// OverlayService so the floating panel talks to the same game process
// without needing its own copy of the field.
object AeroState {
    @Volatile
    var targetPackage: String = "com.axlebolt.standoff2"
}

// Settings for Aero's own floating panel. Local only: these never reach the
// injected process, unlike everything in RootInjector.
object PanelSettings {
    @Volatile
    var opacityPercent: Int = 95
}
