package io.github.yuriyurin.fit3companion
import android.graphics.Bitmap
import io.github.yuriyurin.fit3companion.protocol.Fit3UiPackage
/** Same atlas, clipping and RGB565 alpha blending as the firmware. */
internal object Fit3UiPreview {
 private val atlas get()=OpenSansData.bytes
 private fun u8(at:Int)=atlas[at].toInt() and 255
 private fun u16(at:Int)=u8(at) or (u8(at+1) shl 8)
 private fun u32(at:Int)=u16(at) or (u16(at+2) shl 16)
 private fun glyph(font:Int,code:Int):Int {
  val table=u32(font*8);val count=u16(font*8+4);var lo=0;var hi=count
  while(lo<hi){val mid=(lo+hi)/2;if(u16(table+mid*12)<code)lo=mid+1 else hi=mid}
  return if(lo<count&&u16(table+lo*12)==code)table+lo*12 else if(code==63)-1 else glyph(font,63)
 }
 private data class Metrics(val width:Int,val top:Int,val bottom:Int,val left:Int,val right:Int)
 private fun measure(text:String,font:Int):Metrics {
  var width=0;var top=0;var bottom=0;var first=true;var left=0;var right=0
  for(cp in text.codePoints().toArray()) {
   val g=glyph(font,cp);if(g<0)continue
   val t=atlas[g+3].toInt();val b=t+u8(g+5)
   if(u8(g+4)>0&&u8(g+5)>0){val l=width+atlas[g+2].toInt();val r=l+u8(g+4);if(first||l<left)left=l;if(first||r>right)right=r;if(first||t<top)top=t;if(first||b>bottom)bottom=b;first=false}
   width+=u8(g+6)
  }
  return Metrics(width,top,bottom,left,right)
 }
 private fun fit(text:String,width:Int,height:Int,scale:Int):Int {
  var font=when {scale>=6->3;scale>=3->2;scale>=2->1;else->0}
  while(font>0){val m=measure(text,font);if(m.width<=width&&m.bottom-m.top<=height)break;font--}
  return font
 }
 private fun rgb(c:Int)=((c shr 8) and 0xf800) or ((c shr 5) and 0x7e0) or ((c shr 3) and 31)
 fun bitmap(face:Fit3UiPackage):Bitmap {
  val pixels=IntArray(256*402)
  face.imageOffset?.let { offset->for(i in pixels.indices)pixels[i]=(face.bytes[offset+i*2].toInt() and 255) or ((face.bytes[offset+i*2+1].toInt() and 255) shl 8) }
  fun rect(x:Int,y:Int,w:Int,h:Int,c:Int){for(j in y until y+h)for(i in x until x+w)pixels[j*256+i]=rgb(c)}
  for(layer in face.layers){
   if(layer.type==1||layer.type==5)rect(layer.x,layer.y,layer.width,layer.height,layer.background)
   if(layer.type==1||(layer.type==5&&layer.action==3))continue
   val text=when(layer.type){3->"12:34";4->"07.10.2026";6->"-- °C";else->layer.text}
   val font=fit(text,layer.width,layer.height,layer.scale);val m=measure(text,font)
   var x=layer.x+(layer.width-(m.right-m.left))/2-m.left
   val baseline=layer.y+(layer.height-(m.bottom-m.top))/2-m.top
   val fg=rgb(layer.foreground)
   for(cp in text.codePoints().toArray()) {
    val g=glyph(font,cp);if(g<0)continue
    val gx=x+atlas[g+2].toInt();val gy=baseline+atlas[g+3].toInt();val data=u32(g+8)
    for(j in 0 until u8(g+5))for(k in 0 until u8(g+4)){
     val xx=gx+k;val yy=gy+j
     if(xx !in layer.x until layer.x+layer.width||yy !in layer.y until layer.y+layer.height)continue
     val n=j*u8(g+4)+k;val a=(u8(data+n/2) shr ((n and 1)*4)) and 15;if(a==0)continue
     val bg=pixels[yy*256+xx]
     val r=((fg shr 11)*a+(bg shr 11)*(15-a)+7)/15
     val v=(((fg shr 5) and 63)*a+((bg shr 5) and 63)*(15-a)+7)/15
     val b=((fg and 31)*a+(bg and 31)*(15-a)+7)/15
     pixels[yy*256+xx]=(r shl 11) or (v shl 5) or b
    }
    x+=u8(g+6)
   }
  }
  for(i in pixels.indices){val v=pixels[i];pixels[i]=0xff000000.toInt() or (((v shr 11)*255/31) shl 16) or ((((v shr 5) and 63)*255/63) shl 8) or ((v and 31)*255/31)}
  return Bitmap.createBitmap(pixels,256,402,Bitmap.Config.ARGB_8888)
 }
}
