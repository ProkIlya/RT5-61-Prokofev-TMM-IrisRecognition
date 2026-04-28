package com.example.irisrecognition

data class IrisCode(val leftHash: ByteArray, val rightHash: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IrisCode
        if (!leftHash.contentEquals(other.leftHash)) return false
        if (!rightHash.contentEquals(other.rightHash)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = leftHash.contentHashCode()
        result = 31 * result + rightHash.contentHashCode()
        return result
    }
}

class IrisRecognizer {
    private var registeredCode: IrisCode? = null

    fun register(code: IrisCode) {
        registeredCode = code
    }

    fun identify(code: IrisCode): Boolean {
        val reg = registeredCode ?: return false
        val leftDist = hammingDistance(reg.leftHash, code.leftHash)
        val rightDist = hammingDistance(reg.rightHash, code.rightHash)
        // Порог снижен до 0.31 для большей стабильности
        return leftDist < 0.31 && rightDist < 0.31
    }

    private fun hammingDistance(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size) return 1.0
        var diff = 0
        for (i in a.indices) {
            diff += Integer.bitCount((a[i].toInt() xor b[i].toInt()) and 0xFF)
        }
        return diff.toDouble() / (a.size * 8)
    }
}