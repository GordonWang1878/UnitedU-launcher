package com.uniteduone.launcher

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class GalleryCatalogTest {
    private fun item(id: String = "one", kind: String = "wallpapers") = JSONObject("""{"id":"$id","kind":"$kind","names":{"zh-CN":"中文","zh-TW":"繁體","en":"English"},"order":0,"thumbnail":{"path":"thumb/a.jpg","bytes":1,"sha256":"${"a".repeat(64)}"},"original":{"path":"images/a.jpg","bytes":1,"sha256":"${"b".repeat(64)}","width":1920,"height":1080,"mediaType":"image/jpeg"},"credit":"Licensed"}""").apply { if (kind == "cards") put("packages", JSONArray(listOf("com.example.app"))) }
    private fun catalog(vararg items: JSONObject) = JSONObject().put("schemaVersion", 1).put("revision", 1).put("items", JSONArray(items.toList()))
    private fun rejects(change: (JSONObject) -> Unit) { val json = catalog(item()); change(json); assertTrue(json.toString(), parseGalleryCatalog(json.toString()).isFailure) }
    @Test fun acceptsAllKindsAndUnicodeNames() { val result = parseGalleryCatalog(catalog(item(), item("two", "cards"), item("three", "screensavers")).toString()).getOrThrow(); assertEquals(3, result.items.size); assertEquals("中文", result.items[0].names["zh-CN"]) }
    @Test fun filtersCardsByExactPackageAndSortsStableIds() { val a=item("a", "cards"); val z=item("z", "cards"); val c=parseGalleryCatalog(catalog(z,a,item("wall")).toString()).getOrThrow(); assertEquals(listOf("a","z"),galleryItems(c,BuiltinKind.CARDS,"com.example.app").map { it.id }); assertTrue(galleryItems(c,BuiltinKind.CARDS,"com.example").isEmpty()); assertTrue(galleryItems(c,BuiltinKind.CARDS,null).isEmpty()); assertEquals(1,galleryItems(c,BuiltinKind.WALLPAPERS,null).size) }
    @Test fun rejectsMissingLanguage() = rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("names").remove("en") }
    @Test fun rejectsDuplicateIds() { assertTrue(parseGalleryCatalog(catalog(item(),item()).toString()).isFailure) }
    @Test fun rejectsUnknownSchemaAndKinds() { rejects { it.put("schemaVersion",2) }; rejects { it.getJSONArray("items").getJSONObject(0).put("kind","videos") } }
    @Test fun rejectsNonIntegerAndNegativeRevision() { for (v in listOf(-1,1.5,"1",true,JSONObject.NULL)) rejects { it.put("revision",v) } }
    @Test fun rejectsCoercedNumbers() { for (field in listOf("order")) rejects { it.getJSONArray("items").getJSONObject(0).put(field,"0") }; rejects { it.put("schemaVersion","1") }; rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("original").put("width","1920") } }
    @Test fun rejectsOversizedCatalog() { val c=catalog(); c.put("items",JSONArray((0..2000).map { item("id-$it") })); assertTrue(parseGalleryCatalog(c.toString()).isFailure); val oversized = catalog(item()).put("future", "x".repeat(2*1024*1024)).toString(); JSONObject(oversized); assertTrue(parseGalleryCatalog(oversized).isFailure) }
    @Test fun rejectsInvalidBlobSizesAndHashes() { for (v in listOf(0,-1,524289,"1",1.5)) rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("thumbnail").put("bytes",v) }; rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("original").put("bytes",33554433) }; for(v in listOf("a".repeat(63),"g".repeat(64))) rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("original").put("sha256",v) } }
    @Test fun rejectsInvalidDimensionsAndMedia() { for(v in listOf(0,-1,8193,2.5)) rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("original").put("width",v) }; rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("original").put("mediaType","video/mp4") } }
    @Test fun rejectsInvalidIdsPackagesAndPathsAtomically() { for(v in listOf("../a","A","a".repeat(97),"")) rejects { it.getJSONArray("items").getJSONObject(0).put("id",v) }; val card=item("card","cards"); card.put("packages",JSONArray()); assertTrue(parseGalleryCatalog(catalog(card).toString()).isFailure); rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("thumbnail").put("path","%2e%2e/x") } }
    @Test fun acceptsExactLimitsAndLongRevision() { val i=item(); i.getJSONObject("thumbnail").put("bytes",524288); i.getJSONObject("original").put("bytes",33554432).put("width",8192).put("height",1); val c=catalog(i).put("revision",Long.MAX_VALUE); assertEquals(Long.MAX_VALUE,parseGalleryCatalog(c.toString()).getOrThrow().revision) }
    @Test fun rejectsWrongStringAndContainerTypes() { for(field in listOf("id","kind","credit")) rejects { it.getJSONArray("items").getJSONObject(0).put(field,7) }; rejects { it.getJSONArray("items").getJSONObject(0).put("names",JSONArray()) }; rejects { it.getJSONArray("items").getJSONObject(0).getJSONObject("names").put("en",true) }; rejects { it.put("items",JSONObject()) }; rejects { it.getJSONArray("items").put(0,7) }; assertTrue(parseGalleryCatalog(catalog(item()).toString()+" garbage").isFailure) }
    @Test fun rejectsMalformedCardPackages() { for(values in listOf(listOf("com.example.*"),listOf("com.example.app "),listOf(""),listOf(123))) { val i=item("card","cards").put("packages",JSONArray(values)); assertTrue(parseGalleryCatalog(catalog(i).toString()).isFailure) }; val i=item("card","cards"); i.remove("packages"); assertTrue(parseGalleryCatalog(catalog(i).toString()).isFailure) }
    @Test fun orderPrecedesId() { val a=item("a").put("order",2); val z=item("z").put("order",1); val c=parseGalleryCatalog(catalog(a,z).toString()).getOrThrow(); assertEquals(listOf("z","a"),galleryItems(c,BuiltinKind.WALLPAPERS,null).map { it.id }) }
    @Test fun ignoresUnknownExtraFields() { val c=catalog(item()); c.put("future",true); assertTrue(parseGalleryCatalog(c.toString()).isSuccess) }
    @Test fun sharedV2ImageAppearsInBothPickersAndRoundTrips() {
        val i = item().put("type", "image").put("usages", JSONArray(listOf("wallpapers", "screensavers")))
        i.remove("kind")
        val parsed = parseGalleryCatalog(catalog(i).put("schemaVersion", 2).toString()).getOrThrow()
        assertEquals(listOf("one"), galleryItems(parsed, BuiltinKind.WALLPAPERS, null).map { it.id })
        assertEquals(listOf("one"), galleryItems(parsed, BuiltinKind.SCREENSAVERS, null).map { it.id })
        assertEquals(parsed, parseGalleryCatalog(serializeGalleryCatalog(parsed)).getOrThrow())
    }
    @Test fun v2RejectsVideosAndInvalidUsageCombinations() {
        for (usages in listOf(emptyList(), listOf("screensavers", "screensavers"), listOf("cards", "wallpapers"), listOf("unknown"))) {
            val i = item().put("type", "image").put("usages", JSONArray(usages)); i.remove("kind")
            assertTrue(parseGalleryCatalog(catalog(i).put("schemaVersion", 2).toString()).isFailure)
        }
        val i = item().put("type", "video").put("usages", JSONArray(listOf("screensavers"))); i.remove("kind")
        assertTrue(parseGalleryCatalog(catalog(i).put("schemaVersion", 2).toString()).isFailure)
    }

}
