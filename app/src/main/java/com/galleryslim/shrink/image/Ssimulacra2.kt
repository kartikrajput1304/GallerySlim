package com.galleryslim.shrink.image

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * SSIMULACRA 2 (Jon Sneyers / Cloudinary; reference: libjxl tools/ssimulacra2.cc,
 * fetched Oct 2026). Score: 100 = identical, ~90 = visually lossless at 1:1,
 * ~70 = medium quality. Ported faithfully — XYB colour space with libjxl's opsin
 * constants, 6 scales downsampled in linear RGB, SSIM' + ringing + blurring maps
 * with 1- and 4-norms, the 108 published weights and final nonlinearity — with
 * one approximation: libjxl's recursive Gaussian (sigma 1.5) is replaced by a
 * truncated separable Gaussian of the same sigma, so scores are very close to
 * but not bit-identical with the reference tool.
 *
 * Input: ARGB_8888 pixels in sRGB, same dimensions for both images.
 */
object Ssimulacra2 {
    private const val NUM_SCALES = 6
    private const val C2 = 0.0009f

    // lib/jxl/cms/opsin_params.h
    private const val M00 = 0.30f; private const val M02 = 0.078f; private const val M01 = 1f - M02 - M00
    private const val M10 = 0.23f; private const val M12 = 0.078f; private const val M11 = 1f - M12 - M10
    private const val M20 = 0.24342268924547819f; private const val M21 = 0.20476744424496821f
    private const val M22 = 1f - M20 - M21
    private const val BIAS = 0.0037930732552754493f
    private val NEG_BIAS_CBRT = -cbrt(BIAS)

    private val WEIGHTS = doubleArrayOf(
        0.0, 0.0007376606707406586, 0.0, 0.0, 0.0007793481682867309, 0.0, 0.0, 0.0004371155730107379, 0.0,
        1.1041726426657346, 0.00066284834129271, 0.00015231632783718752, 0.0, 0.0016406437456599754, 0.0,
        1.8422455520539298, 11.441172603757666, 0.0, 0.0007989109436015163, 0.000176816438078653, 0.0,
        1.8787594979546387, 10.94906990605142, 0.0, 0.0007289346991508072, 0.9677937080626833, 0.0,
        0.00014003424285435884, 0.9981766977854967, 0.00031949755934435053, 0.0004550992113792063, 0.0, 0.0,
        0.0013648766163243398, 0.0, 0.0, 0.0, 0.0, 0.0, 7.466890328078848, 0.0, 17.445833984131262,
        0.0006235601634041466, 0.0, 0.0, 6.683678146179332, 0.00037724407979611296, 1.027889937768264,
        225.20515300849274, 0.0, 0.0, 19.213238186143016, 0.0011401524586618361, 0.001237755635509985,
        176.39317598450694, 0.0, 0.0, 24.43300999870476, 0.28520802612117757, 0.0004485436923833408, 0.0, 0.0,
        0.0, 34.77906344483772, 44.835625328877896, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0008680556573291698, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0005313191874358747, 0.0, 0.00016533814161379112,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0004179171803251336, 0.0017290828234722833, 0.0, 0.0020827005846636437,
        0.0, 0.0, 8.826982764996862, 23.19243343998926, 0.0, 95.1080498811086, 0.9863978034400682,
        0.9834382792465353, 0.0012286405048278493, 171.2667255897307, 0.9807858872435379, 0.0, 0.0, 0.0,
        0.0005130064588990679, 0.0, 0.00010854057858411537,
    )

    private class Img(val w: Int, val h: Int) {
        val p = Array(3) { FloatArray(w * h) }
    }

    fun score(orig: IntArray, dist: IntArray, width: Int, height: Int): Double {
        require(orig.size == width * height && dist.size == width * height)
        var lin1 = toLinear(orig, width, height)
        var lin2 = toLinear(dist, width, height)
        val ssim = ArrayList<DoubleArray>()
        val edge = ArrayList<DoubleArray>()
        for (scale in 0 until NUM_SCALES) {
            if (lin1.w < 8 || lin1.h < 8) break
            if (scale > 0) { lin1 = downsample(lin1); lin2 = downsample(lin2) }
            if (lin1.w < 8 || lin1.h < 8) break
            val a = toPositiveXyb(lin1); val b = toPositiveXyb(lin2)
            val s11 = blur(mul(a, a)); val s22 = blur(mul(b, b)); val s12 = blur(mul(a, b))
            val mu1 = blur(a); val mu2 = blur(b)
            ssim += ssimMap(mu1, mu2, s11, s22, s12)
            edge += edgeDiffMap(a, mu1, b, mu2)
        }
        var s = 0.0
        var i = 0
        for (c in 0 until 3) for (sc in ssim.indices) for (n in 0 until 2) {
            s += WEIGHTS[i++] * abs(ssim[sc][c * 2 + n])
            s += WEIGHTS[i++] * abs(edge[sc][c * 4 + n])
            s += WEIGHTS[i++] * abs(edge[sc][c * 4 + n + 2])
        }
        s *= 0.9562382616834844
        s = 2.326765642916932 * s - 0.020884521182843837 * s * s + 6.248496625763138e-05 * s * s * s
        return if (s > 0) 100.0 - 10.0 * s.pow(0.6276336467831387) else 100.0
    }

    private fun srgbToLinear(v: Int): Float {
        val c = v / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    private val LUT = FloatArray(256) { srgbToLinear(it) }

    private fun toLinear(px: IntArray, w: Int, h: Int) = Img(w, h).also { img ->
        for (i in px.indices) {
            val p = px[i]
            img.p[0][i] = LUT[(p ushr 16) and 0xFF]; img.p[1][i] = LUT[(p ushr 8) and 0xFF]; img.p[2][i] = LUT[p and 0xFF]
        }
    }

    private fun downsample(src: Img): Img {
        val ow = (src.w + 1) / 2; val oh = (src.h + 1) / 2
        val out = Img(ow, oh)
        for (c in 0 until 3) for (oy in 0 until oh) for (ox in 0 until ow) {
            var sum = 0f
            for (iy in 0 until 2) for (ix in 0 until 2) {
                val x = minOf(ox * 2 + ix, src.w - 1); val y = minOf(oy * 2 + iy, src.h - 1)
                sum += src.p[c][y * src.w + x]
            }
            out.p[c][oy * ow + ox] = sum * 0.25f
        }
        return out
    }

    private fun toPositiveXyb(lin: Img): Img {
        val out = Img(lin.w, lin.h)
        for (i in 0 until lin.w * lin.h) {
            val r = lin.p[0][i]; val g = lin.p[1][i]; val b = lin.p[2][i]
            val m0 = cbrt(maxOf(0f, M00 * r + M01 * g + M02 * b + BIAS)) + NEG_BIAS_CBRT
            val m1 = cbrt(maxOf(0f, M10 * r + M11 * g + M12 * b + BIAS)) + NEG_BIAS_CBRT
            val m2 = cbrt(maxOf(0f, M20 * r + M21 * g + M22 * b + BIAS)) + NEG_BIAS_CBRT
            val x = 0.5f * (m0 - m1); val y = 0.5f * (m0 + m1); val bb = m2
            // MakePositiveXYB
            out.p[2][i] = (bb - y) + 0.55f
            out.p[0][i] = x * 14f + 0.42f
            out.p[1][i] = y + 0.01f
        }
        return out
    }

    private fun mul(a: Img, b: Img) = Img(a.w, a.h).also { o ->
        for (c in 0 until 3) for (i in o.p[c].indices) o.p[c][i] = a.p[c][i] * b.p[c][i]
    }

    private val KERNEL: FloatArray = run {
        val sigma = 1.5; val radius = 5
        val k = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)).toFloat() }
        val sum = k.sum(); for (j in k.indices) k[j] /= sum; k
    }

    private fun blur(src: Img): Img {
        val r = KERNEL.size / 2
        val out = Img(src.w, src.h)
        val tmp = FloatArray(src.w * src.h)
        for (c in 0 until 3) {
            val p = src.p[c]
            for (y in 0 until src.h) for (x in 0 until src.w) {
                var s = 0f
                for (k in KERNEL.indices) s += KERNEL[k] * p[y * src.w + (x + k - r).coerceIn(0, src.w - 1)]
                tmp[y * src.w + x] = s
            }
            val o = out.p[c]
            for (y in 0 until src.h) for (x in 0 until src.w) {
                var s = 0f
                for (k in KERNEL.indices) s += KERNEL[k] * tmp[(y + k - r).coerceIn(0, src.h - 1) * src.w + x]
                o[y * src.w + x] = s
            }
        }
        return out
    }

    private fun quartic(x: Double): Double { val x2 = x * x; return x2 * x2 }

    private fun ssimMap(m1: Img, m2: Img, s11: Img, s22: Img, s12: Img): DoubleArray {
        val out = DoubleArray(6)
        val inv = 1.0 / (m1.w * m1.h)
        for (c in 0 until 3) {
            var sum0 = 0.0; var sum1 = 0.0
            for (i in m1.p[c].indices) {
                val mu1 = m1.p[c][i]; val mu2 = m2.p[c][i]
                val numM = 1f - (mu1 - mu2) * (mu1 - mu2)
                val numS = 2 * (s12.p[c][i] - mu1 * mu2) + C2
                val denS = (s11.p[c][i] - mu1 * mu1) + (s22.p[c][i] - mu2 * mu2) + C2
                val d = maxOf(1.0 - (numM * numS / denS), 0.0)
                sum0 += d; sum1 += quartic(d)
            }
            out[c * 2] = inv * sum0
            out[c * 2 + 1] = sqrt(sqrt(inv * sum1))
        }
        return out
    }

    private fun edgeDiffMap(img1: Img, mu1: Img, img2: Img, mu2: Img): DoubleArray {
        val out = DoubleArray(12)
        val inv = 1.0 / (img1.w * img1.h)
        for (c in 0 until 3) {
            val s = DoubleArray(4)
            for (i in img1.p[c].indices) {
                val d1 = (1.0 + abs(img2.p[c][i] - mu2.p[c][i])) / (1.0 + abs(img1.p[c][i] - mu1.p[c][i])) - 1.0
                val artifact = maxOf(d1, 0.0); val lost = maxOf(-d1, 0.0)
                s[0] += artifact; s[1] += quartic(artifact); s[2] += lost; s[3] += quartic(lost)
            }
            out[c * 4] = inv * s[0]; out[c * 4 + 1] = sqrt(sqrt(inv * s[1]))
            out[c * 4 + 2] = inv * s[2]; out[c * 4 + 3] = sqrt(sqrt(inv * s[3]))
        }
        return out
    }
}
