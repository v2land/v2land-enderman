package enderman.models

import org.bson.types.ObjectId

case class Location (
                      _id: ObjectId, // for mongodb
                      url: String,
                      redirectFrom: Option[String],
                      referrer: Option[String],
                      clientInfo: ClientInfo,
                    )
