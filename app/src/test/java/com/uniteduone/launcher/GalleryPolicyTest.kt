package com.uniteduone.launcher

import org.junit.Assert.*
import org.junit.Test

class GalleryPolicyTest {
    private val base="https://dl.uniteduone.com/gallery/"
    @Test fun acceptsNormalRelativePath() { assertEquals(base+"images/a.jpg",galleryUrl(base,"images/a.jpg").getOrThrow()) }
    @Test fun rejectsPathTraversal() { for(path in listOf("../latest.json","%2e%2e/a.jpg","https://other.example/a.jpg","/a.jpg","//other.example/a.jpg","a/../b.jpg","a/./b.jpg","a\\b.jpg","a%2fb.jpg","a%5cb.jpg","%252e%252e/a.jpg","a.jpg?x=1","a.jpg#x","", "a//b", "a%00.jpg")) assertTrue(path,galleryUrl(base,path).isFailure) }
    @Test fun rejectsUnsafeBases() { for(b in listOf("http://dl.uniteduone.com/gallery/","https://user@dl.uniteduone.com/gallery/","https://dl.uniteduone.com/gallery/?q=1","https://dl.uniteduone.com/gallery/#x","https://dl.uniteduone.com/gallery/../")) assertTrue(b,galleryUrl(b,"a.jpg").isFailure) }
}
