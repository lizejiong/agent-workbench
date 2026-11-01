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
OUT.write_text(json.dumps(document, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f'Generated {len(document["paths"])} RAG paths')
