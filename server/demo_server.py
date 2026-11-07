"""Локальний навчальний REST API. Запуск: python3 server/demo_server.py.
Доступ емулятора: http://10.0.2.2:8765/ . Не використовувати для реальних акаунтів.
"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import json, secrets, time
accounts={'operator@demo.ua':'Demo12345','viewer@demo.ua':'Demo12345'}
tokens={}
class Handler(BaseHTTPRequestHandler):
 def respond(self,code,data):
  payload=json.dumps(data,ensure_ascii=False).encode();self.send_response(code);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
 def do_POST(self):
  try:
   length=int(self.headers.get('Content-Length','0'))
   if not 0<length<8192:return self.respond(400,{})
   data=json.loads(self.rfile.read(length));email=data.get('email','');password=data.get('password','')
   if self.path=='/register':
    if not data.get('name') or '@' not in email or len(password)<8 or email in accounts:return self.respond(400,{})
    accounts[email]=password
   elif self.path!='/login':return self.respond(404,{})
   if accounts.get(email)!=password:return self.respond(401,{})
   token=secrets.token_urlsafe(24);role='operator' if email=='operator@demo.ua' else 'viewer';expires=int(time.time()*1000)+3600000;tokens[token]=(role,expires)
   self.respond(200,{'token':token,'role':role,'expiresAt':expires})
  except (ValueError,TypeError):self.respond(400,{})
 def do_GET(self):
  if self.path!='/measurements':return self.respond(404,{})
  session=tokens.get(self.headers.get('Authorization','').removeprefix('Bearer '))
  if session is None or session[1]<=int(time.time()*1000):return self.respond(401,{})
  rows=[dict(id=f'post-{n}',name=f'Пост {n.upper()}',type=t,doseRate=d,powerMw=p,latitude=50.45+j*.01,longitude=30.52+j*.01,timestamp=int(time.time()*1000)) for j,(n,t,d,p) in enumerate([('a','Периметр',.12,980),('b','Зал',.18,1010),('c','Контроль',.34,995)])]
  self.respond(200,rows)
 def log_message(self,*args):pass
if __name__=='__main__':
 print('Demo API: http://127.0.0.1:8765');ThreadingHTTPServer(('127.0.0.1',8765),Handler).serve_forever()
