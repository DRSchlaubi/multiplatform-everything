package com.martmists.multiplatform.binary

object ByteData : BinaryData<Byte> {
    override val size: Int = 1
    override fun parseLE(array: ByteArray, offset: Int): Byte {
        return array[offset]
    }

    override fun storeLE(value: Byte, array: ByteArray, offset: Int) {
        array[offset] = value
    }
}
