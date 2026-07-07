package com.lorem.strawberry.core

/**
 * Bluetooth SCO (hands-free) audio control. Implemented by BluetoothScoManager; faked in tests.
 */
interface ScoController {
    /** Open the SCO connection (car mode on). */
    fun start()

    /** Close the SCO connection (car mode off). */
    fun stop()

    /** Play silence to keep SCO alive while no real audio is flowing (e.g. during LLM calls). */
    fun startKeepalive()

    fun stopKeepalive()

    fun destroy()
}
