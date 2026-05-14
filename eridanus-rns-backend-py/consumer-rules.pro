# event_bridge.py calls back into the Kotlin classes of this module by
# reflective name via chaquopy (PyObject.call / .callAttr). R8 must not
# rename these classes or their members — most critically the `call`
# method on the Py*Callback fun-interface implementations, which
# event_bridge.py invokes as `kt_cb.call(...)` for announce / link /
# packet / resource callbacks.
#
# Without this, minified (release) builds throw
#   AttributeError: '<minified>' object has no attribute 'call'
# from RNS Transport's announce job on every received announce — the
# announce arrives but never reaches the app, so hubs never appear.
# Debug builds are unaffected because they aren't minified.
#
# Consumer rule (not app/proguard-rules.pro) so it travels with the
# module and only applies to the python flavor that depends on it.
-keep class tech.torlando.eridanus.rns.py.** { *; }
