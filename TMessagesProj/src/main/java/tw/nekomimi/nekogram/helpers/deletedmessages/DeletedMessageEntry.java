package tw.nekomimi.nekogram.helpers.deletedmessages;

public class DeletedMessageEntry {
    public long    dialogId;
    public int     messageId;
    public String  text;
    public long    fromId;
    public String  fromName;
    public int     date;
    public boolean hasMedia;
    public String  mediaPath;
    public String  mediaType;   // photo/video/voice/round/sticker/gif/document
    public long    deletedAt;
    public boolean isEdited;
    public int     editVersion;
}