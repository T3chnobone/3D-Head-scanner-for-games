import numpy as np, struct, sys
from collections import defaultdict
D='/tmp/ict/ICT-FaceKit/FaceXModel/'
K=40; NV=11248; NQ=11144
def rd(p):
    v=[]
    for l in open(p):
        if l.startswith('v '): v.append(list(map(float,l.split()[1:4])))
    return np.array(v)[:NV]
F=[]
for l in open(D+'generic_neutral_mesh.obj'):
    if l.startswith('f '): F.append([int(t.split('/')[0])-1 for t in l.split()[1:]])
F=np.array(F[:NQ])
V=rd(D+'generic_neutral_mesh.obj')
M=np.stack([rd(D+'identity%03d.obj'%i)-V for i in range(K)])  # K,NV,3
# boundary loops -> caps
ec=defaultdict(int); dirE={}
for q in F:
    for a,b in zip(q,np.roll(q,-1)): ec[(min(a,b),max(a,b))]+=1; dirE[(a,b)]=1
nxt={}
for (a,b) in dirE:
    if ec[(min(a,b),max(a,b))]==1: nxt[a]=b
seen=set(); loops=[]
for s in nxt:
    if s in seen: continue
    L=[s]; seen.add(s); c=nxt[s]
    while c!=s: L.append(c); seen.add(c); c=nxt[c]
    loops.append(L)
tris=[]
for q in F: tris+= [[q[0],q[1],q[2]],[q[0],q[2],q[3]]]
Vall=list(V); Mall=[M[:,i] for i in range(NV)]
flags=np.zeros(NV+len(loops),np.uint8)
flags[:9409]|=1   # face region
for L in loops:
    ci=len(Vall); Vall.append(V[L].mean(0)); Mall.append(M[:,L].mean(1))
    flags[ci]|=8; flags[L]|=8
    for a,b in zip(L,L[1:]+L[:1]): tris.append([b,a,ci])  # boundary edge a->b belongs to a face; cap uses b->a
Vall=np.array(Vall)/100.0; Mall=np.stack(Mall,1)/100.0  # metres; K,N,3
N=len(Vall)
# ears: they are part of the model's "face" area; found as what sticks out sideways at ear height, grown from the
# outermost points
cand=np.zeros(N,bool); cand[:9409]=(np.abs(Vall[:9409,0])>0.062)&(Vall[:9409,2]<0.035)&(Vall[:9409,1]>-0.045)&(Vall[:9409,1]<0.04)
adj=defaultdict(set)
for a_,b_,c_ in tris:
    for u,v in ((a_,b_),(b_,c_),(c_,a_)): adj[u].add(v); adj[v].add(u)
ear=np.zeros(N,bool)
stack=[i for i in range(N) if cand[i] and abs(Vall[i,0])>0.082]
for i in stack: ear[i]=True
while stack:
    u=stack.pop()
    for v in adj[u]:
        if not ear[v] and cand[v] and abs(Vall[v,0])>0.07: ear[v]=True; stack.append(v)
flags[ear]|=2
print('verts',N,'tris',len(tris),'loops',[len(l) for l in loops],'ears',int(ear.sum()))
# closedness check
e2=defaultdict(int)
for t in tris:
    for a,b in zip(t,t[1:]+t[:1]): e2[(a,b)]+=1
bad=sum(1 for (a,b),c in e2.items() if e2.get((b,a),0)!=1 or c!=1)
print('non-manifold directed edges',bad)
sc=np.abs(Mall).reshape(K,-1).max(1)/32767
q=np.round(Mall.reshape(K,-1)/sc[:,None]).astype('<i2')
with open(sys.argv[1],'wb') as f:
    f.write(b'ICTH'); f.write(struct.pack('<4i',1,N,len(tris),K))
    f.write(Vall.astype('<f4').tobytes()); f.write(sc.astype('<f4').tobytes()); f.write(q.tobytes())
    f.write(np.array(tris,'<i4').tobytes()); f.write(flags.tobytes())
# preview obj
import os
od=sys.argv[2]; os.makedirs(od,exist_ok=True)
with open(od+'/head.obj','w') as f:
    for p in Vall: f.write('v %f %f %f\n'%tuple(p))
    f.write('vt 0.5 0.5\n')
    for t in tris: f.write('f %d/1 %d/1 %d/1\n'%(t[0]+1,t[1]+1,t[2]+1))
from PIL import Image
Image.new('RGB',(8,8),(170,170,170)).save(od+'/head_texture.ppm')
np.save('/tmp/ict/flags.npy',flags)
