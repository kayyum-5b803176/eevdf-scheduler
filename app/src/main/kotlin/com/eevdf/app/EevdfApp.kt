package com.eevdf.app

import android.app.Application

/**
 * Application entry point. The reference app's Application was empty; ViewModels
 * obtain their dependencies via AndroidViewModel(application). Future work can
 * promote this to a composition root that wires the clean core ports (see
 * :platform adapters), replacing the data-layer facade bridge.
 */
class EevdfApp : Application()
