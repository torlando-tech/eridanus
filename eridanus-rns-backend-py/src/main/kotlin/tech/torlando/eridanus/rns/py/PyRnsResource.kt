package tech.torlando.eridanus.rns.py

import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import tech.torlando.eridanus.rns.RnsLink
import tech.torlando.eridanus.rns.RnsResource
import tech.torlando.eridanus.rns.RnsResourceAdvertisement
import tech.torlando.eridanus.rns.RnsResourceFactory

class PyRnsResource(val delegate: PyObject) : RnsResource {
    override val data: ByteArray?
        get() = delegate.get("data")?.toJava(ByteArray::class.java)

    override val requestId: ByteArray?
        get() = delegate.get("request_id")?.toJava(ByteArray::class.java)
}

class PyRnsResourceAdvertisement(val delegate: PyObject) : RnsResourceAdvertisement {
    override val requestId: ByteArray?
        get() = delegate.get("request_id")?.toJava(ByteArray::class.java)
}

class PyRnsResourceFactory(private val rns: PyObject) : RnsResourceFactory {
    override fun create(
        data: ByteArray,
        link: RnsLink,
        advertise: Boolean,
        autoCompress: Boolean,
        requestId: ByteArray?,
    ): RnsResource {
        // Reticulum's Resource ctor has a long positional tail
        // (metadata, advertise, auto_compress, callback, progress_callback,
        // timeout, segment_index, original_hash, request_id, ...). Use
        // kwargs so we don't bind to argument-position drift across RNS
        // versions.
        val resource = rns.callAttr(
            "Resource",
            data.toPyBytes(),
            link.asPy(),
            Kwarg("advertise", advertise),
            Kwarg("auto_compress", autoCompress),
            Kwarg("request_id", requestId?.toPyBytes()),
        )
        return PyRnsResource(resource)
    }
}
