package com.liskovsoft.youtubeapi.videoinfo.V2

data class GroupChannel(val id:String,val title:String?,val iconUrl:String?)
data class ChannelGroup(val id:String,val title:String,val iconUrl:String?,val channels:List<GroupChannel>)
class ChannelGroups(initial:List<ChannelGroup> = emptyList(),private val save:(List<ChannelGroup>)->Unit = {},
    private val newId:()->String = {java.util.UUID.randomUUID().toString()}) {
    private var groups=initial.map {it.copy(channels=it.channels.toList())}
    init{require(groups.map{it.id}.distinct().size==groups.size)}
    @Synchronized fun read()=groups.map{it.copy(channels=it.channels.toList())}
    @Synchronized fun find(id:String)=read().firstOrNull{it.id==id}
    private fun write(next:List<ChannelGroup>){save(next.map{it.copy(channels=it.channels.toList())});groups=next}
    @Synchronized fun create(title:String,iconUrl:String?=null,channels:List<GroupChannel> = emptyList()):ChannelGroup {
        require(title.isNotBlank())
        val group=ChannelGroup(newId(),title,iconUrl,channels.distinctBy{it.id}.toList())
        require(groups.none{it.id==group.id})
        write(groups+group);return group.copy(channels=group.channels.toList())
    }
    @Synchronized fun remove(id:String):Boolean {if(groups.none{it.id==id})return false;write(groups.filter{it.id!=id});return true}
    @Synchronized fun rename(id:String,title:String):Boolean {
        if(title.isBlank()||groups.none{it.id==id})return false
        write(groups.map{if(it.id==id)it.copy(title=title)else it});return true
    }
    @Synchronized fun setChannels(id:String,channels:List<GroupChannel>):Boolean {
        if(groups.none{it.id==id})return false
        write(groups.map{if(it.id==id)it.copy(channels=channels.distinctBy{c->c.id}.toList())else it});return true
    }
    @Synchronized fun export():String {
        val items=groups.joinToString(","){group->
            "{"+payload("id" to group.id,"title" to group.title,"iconUrl" to group.iconUrl)+",\"channels\":["+
                group.channels.joinToString(","){"{"+payload("id" to it.id,"title" to it.title,"iconUrl" to it.iconUrl)+"}"}+"]}"
        }
        return "{\"version\":1,\"groups\":[$items]}"
    }
    @JvmOverloads
    @Synchronized fun import(text:String,replace:Boolean=false,format:GroupFormat?=null,
        resolve:((String)->String?)?=null):ReadResult<List<ChannelGroup>> {
        val root=jsonObject(text)
        if (format != GroupFormat.MSC && (format != null || root?.int("version") != 1)) {
            val imported = readChannelGroups(text, format, resolve)
            if (!imported.ok) return ReadResult(error = imported.error)
            return import(ChannelGroups(imported.value!!).export(), replace, GroupFormat.MSC)
        }
        if(root?.int("version")!=1 || root.fields["groups"] !is Json.Arr) return ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_channel_groups"))
        val incoming=ArrayList<ChannelGroup>()
        for(item in root.arr("groups")){
            val group=item as? Json.Obj ?: return ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_channel_group"))
            val id=group.text("id")?.takeIf{it.isNotBlank()} ?: return ReadResult(error=RequestError(RequestFailure.PARSE,"missing_group_id"))
            val title=group.text("title")?.takeIf{it.isNotBlank()} ?: return ReadResult(error=RequestError(RequestFailure.PARSE,"missing_group_title"))
            val channels=ArrayList<GroupChannel>()
            for(raw in group.arr("channels")){
                val channel=raw as? Json.Obj ?: return ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_group_channel"))
                val channelId=channel.text("id")?.takeIf{videoIdAccepted(it)} ?: return ReadResult(error=RequestError(RequestFailure.PARSE,"invalid_channel_id"))
                channels.add(GroupChannel(channelId,channel.text("title"),channel.text("iconUrl")))
            }
            incoming.add(ChannelGroup(id,title,group.text("iconUrl"),channels.distinctBy{it.id}))
        }
        if(incoming.map{it.id}.distinct().size!=incoming.size)return ReadResult(error=RequestError(RequestFailure.PARSE,"duplicate_group_id"))
        val merged=LinkedHashMap<String,ChannelGroup>()
        if(!replace)groups.forEach{merged[it.id]=it}
        incoming.forEach{merged[it.id]=it}
        write(merged.values.toList())
        return ReadResult(read())
    }
}
