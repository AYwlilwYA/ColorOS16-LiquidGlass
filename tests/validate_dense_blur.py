"""Render the actual upstream AGSL. Fixtures are synthetic, not device screenshots.
Requires skia-python, numpy and Pillow; --skia-path can point to an existing environment.
"""
import argparse,hashlib,json,math,re,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--skia-path');p.add_argument('--output',required=True);a=p.parse_args()
if a.skia_path:sys.path.insert(0,str(Path(a.skia_path).resolve()))
import skia
import numpy as np
from PIL import Image,ImageDraw,ImageFilter
root=Path(__file__).resolve().parents[1];out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
relative='app/src/main/kotlin/com/coloros16/liquidglass/liquidglass/LiquidGlassShader.kt'
kotlin=(root/relative).read_text(encoding='utf-8')
source=re.search(r'val AGSL_SOURCE: String = """(.*?)""".trimIndent\(\)',kotlin,re.S)[1]
prior=(root/'tests/fixtures/preblur-baseline.agsl').read_text(encoding='utf-8')
# This patch may change sampling, never the upstream optical math.
restored=source.replace('        uniform float uSourcePreblurred; // Dense F16 input: never blur it again.\n','')
restored=restored.replace('''            if (uSourcePreblurred > 0.5) {
                return float4(uSource.eval(srcCoord(coord, uSourceRect)));
            }
''','')
assert restored==prior,'Unexpected optical changes'
def base(s):return re.sub(r'// @LG-MB-START[^\n]*\n.*?// @LG-MB-END[^\n]*\n','',s,flags=re.S)
effects={n:skia.RuntimeEffect.MakeForShader(s) for n,s in {'old':prior,'new':source,'old-base':base(prior),'new-base':base(source)}.items()}
def render(effect,w,h,radius,blur=16,ready=False,image=None,neutral=False):
 if image is None:
  y,x=np.mgrid[:h,:w];data=np.zeros((h,w,4),np.uint8)
  for c,phase in enumerate([0,2.1,4.2]):data[:,:,c]=np.clip(110+65*np.sin(x/19+phase)+40*np.cos(y/27+phase),0,255)
  data[:,:,3]=255;image=Image.fromarray(data)
 im=skia.Image.fromarray(np.array(image),colorType=skia.ColorType.kRGBA_8888_ColorType)
 sample=im.makeShader(skia.TileMode.kClamp,skia.TileMode.kClamp,skia.SamplingOptions(skia.FilterMode.kLinear))
 b=skia.RuntimeEffectBuilder(effect);b.setChild('uSource',sample);b.setChild('uSourceLowRes',sample)
 uniforms={'uSourceRect':[0,0,w,h],'uSourceLowResRect':[0,0,w,h],'uViewport':[w,h],
 'uCornerRadius':radius,'uCornerWeight':1.3968,'uCornerIsConic':0,'uCornerConicBlend':0,
 'uBlurRadius':blur,'uRefractionHeight':min(w,h)*.4,'uRefractionAmount':0 if neutral else 50,
 'uDepth':2.4,'uDispersion':0 if neutral else .22,'uHighlight':0 if neutral else .6,
 'uHighlightWidth':8,'uHighlightFalloff':3,'uHighlightFloor':.16,'uHighlightInnerAtt':0,'uInnerGlow':0,
 'uVibrancy':1 if neutral else 1.5,'uLightAngle':math.radians(43),'uParallax':[0,0],
 'uMaskRect':[0,0,w,h],'uMaskCornerRadius':0,'uMaskWeight':1.3968,'uMaskOffset':[0,0],
 'uMaskAlpha':0 if neutral else .1,'uMaskColor':[0,0,0,1],'uTilt':[0,1,800,0]}
 if 'uSourcePreblurred' in [u.name for u in effect.uniforms()]:uniforms['uSourcePreblurred']=float(ready)
 for k,v in uniforms.items():b.setUniform(k,v if isinstance(v,list) else float(v))
 surface=skia.Surface.MakeRaster(skia.ImageInfo.Make(w,h,skia.ColorType.kRGBA_F32_ColorType,skia.AlphaType.kPremul_AlphaType))
 c=surface.getCanvas();c.clear(skia.ColorTRANSPARENT);c.drawRect(skia.Rect.MakeWH(w,h),skia.Paint(Shader=b.makeShader()))
 im=surface.makeImageSnapshot();return im.toarray(colorType=skia.ColorType.kRGBA_F32_ColorType,alphaType=skia.AlphaType.kPremul_AlphaType),Image.fromarray(im.toarray(colorType=skia.ColorType.kRGBA_8888_ColorType))
checks=[]
for name,w,h,r in [('round',128,128,64),('wide',280,128,64),('slider',128,280,64),('notification',320,140,30)]:
 for blur in [0,8,64]:
  old,_=render(effects['old-base'],w,h,r,blur)
  legacy,_=render(effects['new-base'],w,h,r,blur)
  assert np.max(np.abs(old-legacy))<2e-6
  dense,_=render(effects['new-base'],w,h,r,blur,True)
  full,_=render(effects['new'],w,h,r,blur,True)
  assert np.isfinite(dense).all() and np.max(np.abs(dense-full))<2e-6
  assert np.max(np.abs(old[:,:,3]-dense[:,:,3]))<2e-6
  checks.append({'shape':name,'blur':blur,'legacyMaxDelta':float(np.max(np.abs(old-legacy))),'coveragePreserved':True,'baseFullEquivalent':True})
# Deliberately align sparse taps to a repeated source pattern: the real aliasing failure mode.
w=h=256;y,x=np.mgrid[:h,:w];v=(((x//8+y//8)%2)*255).astype(np.uint8);rgba=np.dstack([v,v,v,np.full_like(v,255)])
fixture=Image.fromarray(rgba);spread=48/math.sqrt(2)
old,before=render(effects['old-base'],w,h,48,spread,image=fixture,neutral=True)
# Isolated shader-input fixture only; production float filtering is exercised by JVM/Android tests.
prepared=fixture.filter(ImageFilter.GaussianBlur(spread*.8))
new,after=render(effects['new-base'],w,h,48,spread,True,prepared,True)
old_std=float(old[64:-64,64:-64,0].std());new_std=float(new[64:-64,64:-64,0].std())
assert old_std>.1 and new_std<old_std*.02,(old_std,new_std)
sheet=Image.new('RGB',(820,350),(15,20,31));d=ImageDraw.Draw(sheet)
d.text((15,12),'SYNTHETIC SHADER FIXTURE / not a phone screenshot',fill='white')
d.text((15,38),'Upstream sparse taps',fill='white');d.text((425,38),'Dense prepared input, original optics',fill='white')
sheet.paste(before,(15,72));sheet.paste(after,(425,72));sheet.save(out/'mosaic-comparison.png')
hook=(root/'app/src/main/kotlin/com/coloros16/liquidglass/hook/BlurDrawHook.kt').read_text(encoding='utf-8')
assert hook.count('BackdropBinding.resolve(backdropWorker,')==3
assert 'cg.isSnapshotSource, cg.params)' in hook
assert 'renderSource.isSnapshotSource, rawParams)' in hook
assert 'shader, renderParams, sourceShader, lowResShader' in hook
assert 'applied.materialHash != renderParams.hashCode()' in hook
assert 'resolveLiveSource' in hook and 'val currentBitmap = binding.bitmap' in hook
assert 'RectF(snap.region), isSnapshotSource = true' in hook
report={'baseCommit':'aa96e1aeb98f4e3777f4bced265a1b86bf3e10cd','actualAgslSha256':hashlib.sha256(source.encode()).hexdigest(),
 'opticalMathUnchanged':True,'productionBindingCallsites':3,'checks':checks,'aliasingFixture':{'sparseStd':old_std,'preparedStd':new_std},
 'fixturePreblur':'Pillow fixed input, NOT production CPU proof','physicalDeviceTested':False,'colorOs16HookTested':False}
(out/'shader-validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({'passed':True,'cases':len(checks),'aliasingFixture':report['aliasingFixture']}))
