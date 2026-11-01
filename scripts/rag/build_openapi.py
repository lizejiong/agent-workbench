"""Generate the additive RAG OpenAPI contract using only the Python standard library."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'services/backend/src/main/resources/static/openapi/rag.json'
string = {'type': 'string'}
uuid = {'type': 'string', 'format': 'uuid'}
ids = {'type': 'array', 'maxItems': 64, 'uniqueItems': True, 'items': uuid, 'nullable': True}
search = {'type': 'object', 'additionalProperties': False, 'required': ['query'], 'properties': {
    'query': dict(string, minLength=1, maxLength=16000), 'kbIds': ids,
    'documentIds': dict(ids, minItems=1, maxItems=500), 'topK': {'type': 'integer', 'minimum': 1, 'maximum': 200, 'default': 5},
    'strategy': dict(string, enum=['AUTO', 'VECTOR', 'KEYWORD', 'HYBRID']), 'rerank': {'type': 'boolean'},
    'minScore': {'type': 'number', 'minimum': -1, 'maximum': 1}, 'minRerankScore': {'type': 'number'},
    'conversationId': dict(uuid, nullable=True)}}
query = {'type': 'object', 'properties': {key: string for key in ['originalQuery', 'effectiveQuery', 'promptVersion', 'promptHash', 'termVersion']}}
query['properties'].update({'subQueries': {'type': 'array', 'maxItems': 3, 'items': string}, 'kbIds': ids,
    'needsClarification': {'type': 'boolean'}, 'clarificationQuestion': dict(string, nullable=True),
    'degraded': {'type': 'boolean'}, 'fallbackReason': dict(string, nullable=True), 'model': dict(string, nullable=True)})
retrieval = {'type': 'object', 'properties': {'status': dict(string, enum=['READY', 'NEEDS_CLARIFICATION', 'NO_EVIDENCE', 'UNAVAILABLE']),
    'query': {'$ref': '#/components/schemas/QueryPlan'},
    'retrieval': {'nullable': True, 'allOf': [{'$ref': 'knowledge-base.yaml#/components/schemas/Retrieval'}]}}}
def operation(name, request, response):
    result = {'operationId': name, 'requestBody': {'required': True, 'content': {'application/json': {'schema': {'$ref': '#/components/schemas/'+request}}}},
        'responses': {'200': {'description': '成功；结果携带实际状态、授权证据和降级信息', 'headers': {'Cache-Control': {'schema': {'type':'string','enum':['no-store']}}},
            'content': {'application/json': {'schema': {'type': 'object', 'properties': {'code': string, 'message': string, 'requestId': string, 'data': {'$ref': '#/components/schemas/'+response}}}}}}}}
    for status in ['400','401','404','409','422','429','500','502','503','504']:
        result['responses'][status] = {'description': '脱敏业务错误；故障不会伪装为无证据', 'content': {'application/json': {'schema': {'$ref':'knowledge-base.yaml#/components/schemas/Envelope'}}}}
    return result
document = {'openapi': '3.0.3', 'info': {'title': 'Java RAG API', 'version': '2026-10-31'},
    'security': [{'basicAuth': []}], 'paths': {'/api/rag/retrieve': {'post': operation('retrieveRag', 'RagRetrieveRequest', 'RagRetrieval')}},
    'components': {'securitySchemes': {'basicAuth': {'type':'http','scheme':'basic'}},
        'schemas': {'RagRetrieveRequest': search, 'QueryPlan': query, 'RagRetrieval': retrieval}}}
snapshot = {'type':'object','properties':{key:dict(string) for key in ['evidenceId','documentName','content','fileUrl','sourceContentHash','snapshotHash']}}
snapshot['properties'].update({key:uuid for key in ['kbId','documentId','chunkId']})
snapshot['properties'].update({'generation':{'type':'integer','format':'int64'},'chunkIndex':{'type':'integer'},'sourceMetadata':{'type':'object'},
    'truncated':{'type':'boolean'},'adjacent':{'type':'boolean'},'parentChunkId':dict(uuid,nullable=True),
    'subQueries':{'type':'array','items':string},'fusionScore':{'type':'number','nullable':True},'rerankScore':{'type':'number','nullable':True}})
context = {'type':'object','properties':{'evidence':{'type':'array','maxItems':50,'items':{'$ref':'#/components/schemas/EvidenceSnapshot'}},
    'estimatedTokens':{'type':'integer','format':'int64'},'inputTokenBudget':{'type':'integer'},'estimated':{'type':'boolean'},
    'truncated':{'type':'boolean'},'strategyVersion':string,'contentHash':string}}
document['components']['schemas'].update({'EvidenceSnapshot':snapshot,'EvidenceContext':context})
retrieval['properties']['context']={'nullable':True,'allOf':[{'$ref':'#/components/schemas/EvidenceContext'}]}
schemas = document['components']['schemas']
answer_request = json.loads(json.dumps(search))
answer_request['required'] = ['question']
answer_request['properties']['question'] = answer_request['properties'].pop('query')
statuses = ['RUNNING','COMPLETED','NO_EVIDENCE','NEEDS_CLARIFICATION','CITATION_INVALID','CANCELLED','TIMED_OUT','FAILED']
schemas.update({'AnswerRequest':answer_request,
    'Answer':{'type':'object','properties':{
        'answerId':uuid,'conversationId':uuid,'requestId':string,'status':dict(string,enum=statuses),'answer':string,
        'citations':{'type':'array','items':{'type':'object','properties':{'evidenceId':string,'documentId':uuid,'chunkId':uuid,'generation':{'type':'integer','format':'int64'},'snapshotHash':string}}},
        'sources':{'type':'array','items':{'$ref':'#/components/schemas/EvidenceSnapshot'}},
        'unavailableSources':{'type':'array','items':string},'degraded':{'type':'boolean'},'citationValidation':dict(string,enum=['VALID','MISSING_REFERENCE','UNKNOWN_REFERENCE','NOT_APPLICABLE']),
        'usage':{'type':'object','nullable':True},'modelCalls':{'type':'array','items':{'type':'object','properties':{'purpose':string,'model':string,'promptVersion':string,'promptHash':string,'usage':{'type':'object','nullable':True}}}},
        'query':{'$ref':'#/components/schemas/QueryPlan'},'retrievalTrace':{'$ref':'knowledge-base.yaml#/components/schemas/RetrievalTrace'},
        'evidenceContextHash':dict(string,nullable=True),'repairAttempted':{'type':'boolean'},'errorCode':dict(string,nullable=True)}},
    'AnswerRun':{'type':'object','properties':{'answerId':uuid,'conversationId':uuid,'question':string,'status':dict(string,enum=statuses),'answer':string,
        'result':{'nullable':True,'allOf':[{'$ref':'#/components/schemas/Answer'}]},'errorCode':dict(string,nullable=True),'createdAt':dict(string,format='date-time'),'updatedAt':dict(string,format='date-time')}},
    'Conversation':{'type':'array','maxItems':20,'items':{'$ref':'#/components/schemas/AnswerRun'}}})
document['info']['version']='2026-11-01'
document['paths']['/api/rag/answer']={'post':operation('answerRag','AnswerRequest','Answer')}
for path,name,response in [('/api/rag/runs/{id}','getRagRun','AnswerRun'),('/api/rag/conversations/{id}','getRagConversation','Conversation')]:
    op=operation(name,'AnswerRequest',response)
    del op['requestBody']
    op['parameters']=[{'name':'id','in':'path','required':True,'schema':uuid}]
    document['paths'][path]={'get':op}
schemas.update({
    'StreamMeta':{'type':'object','required':['requestId','runId','answerId','conversationId','deadline'],'properties':{
        'requestId':string,'runId':uuid,'answerId':uuid,'conversationId':uuid,'deadline':dict(string,format='date-time')}},
    'StreamRetrieval':{'type':'object','properties':{'phase':dict(string,enum=['COMPLETED','FAILED']),
        'status':dict(string,enum=['READY','NO_EVIDENCE','NEEDS_CLARIFICATION','UNAVAILABLE']),'degraded':{'type':'boolean'},'strategy':string}},
    'StreamMessage':{'type':'object','required':['text'],'properties':{'text':string}},
    'StreamError':{'type':'object','properties':{'requestId':string,'code':string,'message':string}},
    'StreamFinish':{'type':'object','required':['status','persisted','run'],'properties':{'status':dict(string,enum=statuses+['UNKNOWN']),
        'persisted':{'type':'boolean'},'run':{'$ref':'#/components/schemas/AnswerRun'}}},
    'StreamDone':{'type':'object','properties':{'requestId':string}},
    'CancelResult':{'type':'object','properties':{'runId':uuid,'conversationId':uuid,'status':dict(string,enum=statuses)}}})
stream=operation('streamRagAnswer','AnswerRequest','Answer')
stream['responses']['200']['description']='SSE：meta → retrieval → sources → message* → error? → finish → done；done 只表示流结束。断连不保证尾事件送达，读取运行状态确认。'
stream['responses']['200']['content']={'text/event-stream':{'schema':{'type':'string'}}}
stream['x-sse-events']={name:{'$ref':'#/components/schemas/'+schema} for name,schema in {
    'meta':'StreamMeta','retrieval':'StreamRetrieval','sources':'EvidenceContext','message':'StreamMessage',
    'error':'StreamError','finish':'StreamFinish','done':'StreamDone'}.items()}
stream['x-sse-events']['sources']['description']='无上下文时为 {evidence:[]}；其余同 EvidenceContext'
document['paths']['/api/rag/answer/stream']={'post':stream}
cancel=operation('cancelRagRun','AnswerRequest','CancelResult')
del cancel['requestBody']
cancel['parameters']=[{'name':'id','in':'path','required':True,'schema':uuid}]
document['paths']['/api/rag/runs/{id}/cancel']={'post':cancel}
schemas['DelegationRequest']={'type':'object','additionalProperties':False,'required':['serviceId','agentSessionId'],
    'properties':{'serviceId':dict(string,pattern='^[a-z0-9-]{1,80}$'),'agentSessionId':uuid,'kbIds':ids,'documentIds':dict(ids,minItems=1,maxItems=500)}}
schemas['Delegation']={'type':'object','properties':{'delegationId':uuid,'delegationToken':dict(string,format='password',description='只返回一次，禁止进入模型或日志'),
    'serviceId':string,'agentSessionId':uuid,'tenantId':string,'userId':string,'kbIds':ids,'documentIds':ids,'expiresAt':dict(string,format='date-time')}}
schemas['InternalRetrieveRequest']=json.loads(json.dumps(search))
del schemas['InternalRetrieveRequest']['properties']['conversationId']
schemas['AgentEvidence']={'type':'object','properties':{
    'status':dict(string,enum=['READY','NO_EVIDENCE','NEEDS_CLARIFICATION','UNAVAILABLE']),
    'requestId':string,'traceId':dict(string,pattern='^[0-9a-f]{32}$'),'clarificationQuestion':dict(string,nullable=True),'degraded':{'type':'boolean'},
    'strategy':string,'rerankStatus':string,'evidence':{'type':'array','items':{'$ref':'#/components/schemas/EvidenceSnapshot'}},
    'scores':{'type':'object','additionalProperties':{'type':'object','properties':{'score':{'type':'number'},'scoreType':string,'fusionScore':{'type':'number','nullable':True},'rerankScore':{'type':'number','nullable':True}}}},
    'contextHash':dict(string,nullable=True),'versions':{'type':'object','properties':{'pipeline':dict(string,nullable=True),'context':dict(string,nullable=True),
        'queryPrompt':string,'queryPromptHash':string,'indexes':{'type':'array','items':{'type':'object','properties':{'kbId':uuid,'embeddingModelId':dict(string,nullable=True),'embeddingConfigRevision':dict(string,nullable=True),'aiConfigRevision':dict(string,nullable=True)}}}}}}}
issue=operation('issueAgentDelegation','DelegationRequest','Delegation')
issue['responses']['201']=issue['responses'].pop('200')
document['paths']['/api/rag/delegations']={'post':issue}
revoke=operation('revokeAgentDelegation','DelegationRequest','Delegation')
del revoke['requestBody']
revoke['parameters']=[{'name':'id','in':'path','required':True,'schema':uuid}]
revoke['responses'].pop('200');revoke['responses']['204']={'description':'已撤销本人委托；重复撤销幂等'}
document['paths']['/api/rag/delegations/{id}']={'delete':revoke}
document['components']['securitySchemes'].update({'serviceBearer':{'type':'http','scheme':'bearer'},'userDelegation':{'type':'apiKey','in':'header','name':'X-Rag-Delegation'}})
internal=operation('retrieveAgentEvidence','InternalRetrieveRequest','AgentEvidence')
internal['security']=[{'serviceBearer':[],'userDelegation':[]}]
internal['parameters']=[{'name':name,'in':'header','required':required,'schema':dict(string,**extra)} for name,required,extra in [
    ('X-Rag-Service',True,{'pattern':'^[a-z0-9-]{1,80}$'}),('X-Request-Id',True,{'pattern':'^[A-Za-z0-9._:-]{1,128}$'}),
    ('X-Rag-Deadline',True,{'format':'date-time'}),('traceparent',False,{'pattern':'^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'})]]
internal['responses']['403']={'description':'请求范围超出委托；不会扩大到其他知识库'}
internal['responses']['200']['headers']['traceparent']={'schema':string}
document['paths']['/internal/rag/retrieve']={'post':internal}
tool_cancel=json.loads(json.dumps(internal));del tool_cancel['requestBody']
tool_cancel['operationId']='cancelAgentRetrieval'
tool_cancel['parameters'].append({'name':'requestId','in':'path','required':True,'schema':dict(string,pattern='^[A-Za-z0-9._:-]{1,128}$')})
next(p for p in tool_cancel['parameters'] if p['name']=='X-Request-Id')['required']=False
tool_cancel['responses'].pop('200');tool_cancel['responses']['202']={'description':'取消已登记；同一委托不能取消其他委托的请求；保留取消先到的墓碑'}
document['paths']['/internal/rag/requests/{requestId}/cancel']={'post':tool_cancel}
OUT.write_text(json.dumps(document, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f'Generated {len(document["paths"])} RAG paths')
